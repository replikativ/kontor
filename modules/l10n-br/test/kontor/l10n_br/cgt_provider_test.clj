(ns kontor.l10n-br.cgt-provider-test
  "Tests for the BR CGT provider (ADR-102 + ADR-101).

   Worked examples:

   A. PF residential sale w/ 75 % reinvestment — R$ 1.2M sale, R$ 800k
      basis, R$ 900k re-invested within 180 days → 75 % of the
      R$ 400k gain exempt → R$ 100k taxable → 15 % = R$ 15 000.
   B. PF mixed B3 month — May 2026 has swing ações R$ 18k aggregate
      sale price (under R$ 20k → exempt), day-trade R$ 12k gain →
      R$ 2 400 day-trade tax minus R$ 120 IRRF dedo-duro."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.disposal :as disposal]
            [kontor.disposal.provider :as disp-provider]
            [kontor.l10n-br.cgt-provider :as br-cgt]
            [kontor.l10n-br.cgt-statute :as cgt-statute]
            [kontor.tax.period-tax-provider :as ptp]))

;; ============================================================================
;; Fixture
;; ============================================================================

(defn- fresh
  "Fresh test DB with the disposal companion, BR CGT statute, BRL
   commodity, and one HOLDCO entity."
  []
  (let [conn (core/create-test-db)]
    (disposal/install! conn)
    (cgt-statute/install! conn)
    (d/transact conn [{:kontor.commodity/symbol "BRL" :kontor.commodity/name "Brazilian Real"
                       :kontor.commodity/precision 2}
                      {:kontor.entity/code "HOLDCO" :kontor.entity/name "HoldCo BR"
                       :kontor.entity/kind :company :kontor.entity/country "BR"
                       :kontor.entity/functional-commodity [:kontor.commodity/symbol "BRL"]}])
    conn))

(def ^:private brl [:kontor.commodity/symbol "BRL"])
(def ^:private holdco [:kontor.entity/code "HOLDCO"])

(defn- record!
  [conn opts]
  (disposal/record-disposal!
   conn (merge {:entity          holdco
                :kind            :sale
                :subject         brl
                :subject-kind    :fixed-asset
                :recorded-by-uid "test"
                :proceeds        {:amount 0M :commodity brl}
                :basis           {:amount 0M :commodity brl}}
               opts)))

(defn- holdco-eid [conn]
  (d/q '[:find ?e . :where [?e :kontor.entity/code "HOLDCO"]] (d/db conn)))

(defn- run-provider
  [conn kind period & [extra-ctx]]
  (let [source   (disp-provider/datahike-provider conn)
        provider (case kind
                   :individual  (br-cgt/br-individual-cgt-provider {:source source})
                   :corporation (br-cgt/br-corporate-cgt-provider  {:source source}))]
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

;; ============================================================================
;; §1. Plumbing — empty source, kind validation, asset-class routing
;; ============================================================================

(deftest empty-source-returns-zero-components
  (testing "an entity with no disposals returns an empty :components vec"
    (let [conn (fresh)
          facts (run-provider conn :individual p2026)]
      (is (empty? (:components facts))))))

(deftest kind-validation
  (testing "the provider rejects unknown :kind"
    (let [conn (fresh)
          source (disp-provider/datahike-provider conn)
          bad (br-cgt/->BRCapitalGainsTaxProvider
               :bogus source :br-rfb :BRL "" :bogus {})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #":kind must be"
                            (ptp/period-tax-facts
                             bad {:db (d/db conn)
                                  :entity (holdco-eid conn)
                                  :period p2026}))))))

(deftest lucro-presumido-informative-error
  (testing ":lucro-presumido throws informative v1-unsupported error"
    (let [conn (fresh)]
      (record! conn {:external-id "lp-1"
                     :acquired-on #inst "2025-01-01"
                     :disposed-on #inst "2026-06-15"
                     :asset-class :br-real-estate-comercial
                     :proceeds {:amount 1000000M :commodity brl}
                     :basis    {:amount  400000M :commodity brl}})
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"v1 supports only :lucro-real"
                            (run-provider conn :corporation p2026
                                          {:tax-unit {:tax-regime :lucro-presumido}}))))))

;; ============================================================================
;; §2. Lane A — PF ganho de capital — four-bracket progressive ladder
;; ============================================================================

(deftest four-bracket-ladder-small-gain-15pct
  (testing "R$ 100k gain → first bracket → 15 % → R$ 15 000"
    (let [conn (fresh)]
      (record! conn {:external-id "ga-100k"
                     :acquired-on #inst "2020-01-01"
                     :disposed-on #inst "2026-06-15"
                     :asset-class :br-real-estate-comercial
                     :proceeds {:amount 500000M :commodity brl}
                     :basis    {:amount 400000M :commodity brl}})
      (let [facts (run-provider conn :individual p2026)
            cmp   (component-by-lane facts :br-ganho-capital)]
        (is (some? cmp))
        (is (== 100000M (-> cmp :base :amount)))
        (is (== 15000M (-> cmp :liability :amount)))
        (is (= 4600 (get-in cmp [:jurisdiction-specific-codes :darf])))))))

(deftest four-bracket-ladder-cliff-at-R5M
  (testing "exactly R$ 5M gain → still entirely in bracket I → 15 % = R$ 750 000"
    (let [conn (fresh)]
      (record! conn {:external-id "ga-5m"
                     :acquired-on #inst "2020-01-01"
                     :disposed-on #inst "2026-06-15"
                     :asset-class :br-real-estate-comercial
                     :proceeds {:amount 6000000M :commodity brl}
                     :basis    {:amount 1000000M :commodity brl}})
      (let [facts (run-provider conn :individual p2026)
            cmp   (component-by-lane facts :br-ganho-capital)]
        (is (== 5000000M (-> cmp :base :amount)))
        (is (== 750000M (-> cmp :liability :amount))
            "R$ 5M × 15 % = R$ 750 000 — exact boundary, no spill into bracket II")))))

(deftest four-bracket-ladder-cliff-at-R10M
  (testing "exactly R$ 10M gain → bracket I + II → R$ 1 625 000"
    (let [conn (fresh)]
      (record! conn {:external-id "ga-10m"
                     :acquired-on #inst "2020-01-01"
                     :disposed-on #inst "2026-06-15"
                     :asset-class :br-real-estate-comercial
                     :proceeds {:amount 12000000M :commodity brl}
                     :basis    {:amount  2000000M :commodity brl}})
      (let [facts (run-provider conn :individual p2026)
            cmp   (component-by-lane facts :br-ganho-capital)]
        (is (== 10000000M (-> cmp :base :amount)))
        ;; 5M × 15% + 5M × 17.5% = 750k + 875k = 1 625 000
        (is (== 1625000M (-> cmp :liability :amount)))))))

(deftest four-bracket-ladder-cliff-at-R30M
  (testing "exactly R$ 30M gain → brackets I + II + III → R$ 5 625 000"
    (let [conn (fresh)]
      (record! conn {:external-id "ga-30m"
                     :acquired-on #inst "2010-01-01"
                     :disposed-on #inst "2026-06-15"
                     :asset-class :br-unlisted-share
                     :proceeds {:amount 32000000M :commodity brl}
                     :basis    {:amount  2000000M :commodity brl}})
      (let [facts (run-provider conn :individual p2026)
            cmp   (component-by-lane facts :br-ganho-capital)]
        (is (== 30000000M (-> cmp :base :amount)))
        ;; 5M × 15% + 5M × 17.5% + 20M × 20% = 750k + 875k + 4 000k = 5 625 000
        (is (== 5625000M (-> cmp :liability :amount)))))))

(deftest four-bracket-ladder-above-R30M
  (testing "R$ 35M gain → spills into bracket IV → R$ 6 750 000"
    (let [conn (fresh)]
      (record! conn {:external-id "ga-35m"
                     :acquired-on #inst "2010-01-01"
                     :disposed-on #inst "2026-06-15"
                     :asset-class :br-unlisted-share
                     :proceeds {:amount 36000000M :commodity brl}
                     :basis    {:amount  1000000M :commodity brl}})
      (let [facts (run-provider conn :individual p2026)
            cmp   (component-by-lane facts :br-ganho-capital)]
        (is (== 35000000M (-> cmp :base :amount)))
        ;; + 5M × 22.5 % = 1.125M added; 5.625M + 1.125M = 6 750 000
        (is (== 6750000M (-> cmp :liability :amount)))))))

;; ============================================================================
;; §3. Lane A — art. 39 residence-reinvestment exemption
;; ============================================================================

(deftest residence-reinvest-exemption-worked-example-a
  (testing "R$ 1.2M sale w/ R$ 900k re-invested → 75% exempt → R$ 15 000 tax"
    (let [conn (fresh)]
      (record! conn {:external-id "rr-1"
                     :acquired-on #inst "2018-06-01"
                     :disposed-on #inst "2026-03-15"
                     :asset-class :br-real-estate-residencial
                     :residence?  true
                     :elective-regime #{:br-residence-reinvest}
                     :proceeds {:amount 1200000M :commodity brl}
                     :basis    {:amount  800000M :commodity brl}
                     ;; R$ 900k re-invested → reduce gain by 75 %.
                     ;; `:into-asset` is required by the disposal companion's
                     ;; rollover triple — we use a generic ref (the BRL
                     ;; commodity) as a placeholder; a real consumer wires
                     ;; the replacement-property asset eid.
                     :rollover {:into-asset brl
                                :amount 900000M :commodity brl
                                :deadline #inst "2026-09-11"}})
      (let [facts (run-provider conn :individual p2026)
            cmp   (component-by-lane facts :br-ganho-capital)]
        (is (some? cmp))
        (is (== 100000M (-> cmp :base :amount))
            "400k gain × 25 % non-reinvested fraction = 100k taxable")
        (is (== 15000M (-> cmp :liability :amount))
            "100k × 15 % bracket I = R$ 15 000")))))

(deftest residence-reinvest-full-exemption
  (testing "100 % of proceeds reinvested → 0 taxable gain"
    (let [conn (fresh)]
      (record! conn {:external-id "rr-2"
                     :acquired-on #inst "2018-06-01"
                     :disposed-on #inst "2026-03-15"
                     :asset-class :br-real-estate-residencial
                     :residence?  true
                     :elective-regime #{:br-residence-reinvest}
                     :proceeds {:amount 800000M :commodity brl}
                     :basis    {:amount 500000M :commodity brl}
                     :rollover {:into-asset brl
                                :amount 800000M :commodity brl
                                :deadline #inst "2026-09-11"}})
      (let [facts (run-provider conn :individual p2026)
            cmp   (component-by-lane facts :br-ganho-capital)]
        (is (some? cmp) "audit-trail component still emitted")
        (is (zero? (-> cmp :base :amount)))
        (is (zero? (-> cmp :liability :amount)))))))

;; ============================================================================
;; §4. Pequeno-valor R$ 35k monthly aggregate isenção (Lei 9.250/95 art. 22)
;; ============================================================================

(deftest pequeno-valor-isenção-applies-when-monthly-sum-under-cap
  (testing "two :br-equity-comum sales totalling R$ 30k in March → fully exempt"
    (let [conn (fresh)]
      (record! conn {:external-id "pv-a"
                     :acquired-on #inst "2024-01-01"
                     :disposed-on #inst "2026-03-10"
                     :asset-class :br-equity-comum
                     :proceeds {:amount 15000M :commodity brl}
                     :basis    {:amount  8000M :commodity brl}})
      (record! conn {:external-id "pv-b"
                     :acquired-on #inst "2024-01-01"
                     :disposed-on #inst "2026-03-20"
                     :asset-class :br-equity-comum
                     :proceeds {:amount 15000M :commodity brl}
                     :basis    {:amount  6000M :commodity brl}})
      (let [facts (run-provider conn :individual p2026)
            cmp   (component-by-lane facts :br-ganho-capital)]
        (is (some? cmp))
        (is (zero? (-> cmp :base :amount))
            "30k total monthly sale price ≤ 35k cap → fully exempt")
        (is (zero? (-> cmp :liability :amount)))))))

(deftest pequeno-valor-isenção-not-applied-when-monthly-sum-over-cap
  (testing "monthly sum > R$ 35k → ALL disposals in month taxable (binary rule)"
    (let [conn (fresh)]
      (record! conn {:external-id "pv-c"
                     :acquired-on #inst "2024-01-01"
                     :disposed-on #inst "2026-04-10"
                     :asset-class :br-equity-comum
                     :proceeds {:amount 25000M :commodity brl}
                     :basis    {:amount 10000M :commodity brl}})
      (record! conn {:external-id "pv-d"
                     :acquired-on #inst "2024-01-01"
                     :disposed-on #inst "2026-04-20"
                     :asset-class :br-equity-comum
                     :proceeds {:amount 15000M :commodity brl}
                     :basis    {:amount  5000M :commodity brl}})
      (let [facts (run-provider conn :individual p2026)
            cmp   (component-by-lane facts :br-ganho-capital)]
        ;; April aggregate = 40k > 35k → both disposals fully taxable.
        ;; Gain = (25k - 10k) + (15k - 5k) = 25k
        (is (== 25000M (-> cmp :base :amount)))
        ;; 25k × 15 % = 3750
        (is (== 3750M (-> cmp :liability :amount)))))))

;; ============================================================================
;; §5. Lane B-swing — R$ 20k bolsa swing isenção + 15 % rate
;; ============================================================================

(deftest swing-isenção-under-R20k-monthly-cap
  (testing "single swing sale at R$ 18k/month → exempt"
    (let [conn (fresh)]
      (record! conn {:external-id "sw-a"
                     :acquired-on #inst "2025-01-01"
                     :disposed-on #inst "2026-05-15"
                     :asset-class :br-renda-variavel-long
                     :proceeds {:amount 18000M :commodity brl}
                     :basis    {:amount 10000M :commodity brl}})
      (let [facts (run-provider conn :individual p2026)
            cmp   (component-by-lane facts :br-renda-variavel-long)]
        (is (some? cmp))
        (is (zero? (-> cmp :base :amount))
            "18k monthly sale price ≤ 20k → exempt")
        (is (zero? (-> cmp :liability :amount)))))))

(deftest swing-isenção-over-R20k-fully-taxable
  (testing "swing month aggregating > R$ 20k → all taxable @ 15 %"
    (let [conn (fresh)]
      (record! conn {:external-id "sw-big"
                     :acquired-on #inst "2025-01-01"
                     :disposed-on #inst "2026-05-15"
                     :asset-class :br-renda-variavel-long
                     :proceeds {:amount 50000M :commodity brl}
                     :basis    {:amount 30000M :commodity brl}})
      (let [facts (run-provider conn :individual p2026)
            cmp   (component-by-lane facts :br-renda-variavel-long)]
        (is (== 20000M (-> cmp :base :amount)))
        (is (== 3000M (-> cmp :liability :amount))
            "20k gain × 15 % = 3000")))))

(deftest swing-irrf-prepayment-flows-to-prepaid
  (testing "broker IRRF dedo-duro (0.005 % of price) flows to component :prepaid"
    (let [conn (fresh)]
      (record! conn {:external-id "sw-irrf"
                     :acquired-on #inst "2025-01-01"
                     :disposed-on #inst "2026-05-15"
                     :asset-class :br-renda-variavel-long
                     :proceeds {:amount 50000M :commodity brl}
                     :basis    {:amount 30000M :commodity brl}})
      (let [facts (run-provider conn :individual p2026
                                {:inputs {:br-irrf-withheld {:swing 2.50M}}})
            cmp   (component-by-lane facts :br-renda-variavel-long)]
        (is (== 2.50M (-> cmp :prepaid :amount)))
        ;; balance = liability − prepaid
        (let [bal (ptp/balance facts)]
          (is (== 2997.50M (:amount bal))))))))

;; ============================================================================
;; §6. Lane B-day — 20 % rate, NO isenção
;; ============================================================================

(deftest day-trade-20pct-no-isenção
  (testing "day-trade gain → flat 20 %, even at small amounts"
    (let [conn (fresh)]
      (record! conn {:external-id "dt-1"
                     :acquired-on #inst "2026-05-15"
                     :disposed-on #inst "2026-05-15"
                     :asset-class :br-renda-variavel-day
                     :proceeds {:amount 18000M :commodity brl}
                     :basis    {:amount  6000M :commodity brl}})
      (let [facts (run-provider conn :individual p2026)
            cmp   (component-by-lane facts :br-renda-variavel-day)]
        (is (some? cmp))
        (is (== 12000M (-> cmp :base :amount)))
        ;; 12k × 20% = 2400
        (is (== 2400M (-> cmp :liability :amount)))))))

(deftest day-trade-irrf-flows-to-prepaid
  (testing "day-trade IRRF (1 % of monthly gain) flows to :prepaid"
    (let [conn (fresh)]
      (record! conn {:external-id "dt-irrf"
                     :acquired-on #inst "2026-05-15"
                     :disposed-on #inst "2026-05-15"
                     :asset-class :br-renda-variavel-day
                     :proceeds {:amount 18000M :commodity brl}
                     :basis    {:amount  6000M :commodity brl}})
      (let [facts (run-provider conn :individual p2026
                                {:inputs {:br-irrf-withheld {:day 120M}}})
            cmp   (component-by-lane facts :br-renda-variavel-day)]
        (is (== 120M (-> cmp :prepaid :amount)))
        ;; DARF due = 2400 − 120 = 2280
        (let [bal (ptp/balance facts)]
          (is (== 2280M (:amount bal))))))))

;; ============================================================================
;; §7. Carry-forward — losses offset only within same lane
;; ============================================================================

(deftest carryforward-applies-within-lane
  (testing "BR carry-in losses offset only the matching lane's gain"
    (let [conn (fresh)]
      (record! conn {:external-id "cf-a"
                     :acquired-on #inst "2020-01-01"
                     :disposed-on #inst "2026-06-15"
                     :asset-class :br-real-estate-comercial
                     :proceeds {:amount 600000M :commodity brl}
                     :basis    {:amount 100000M :commodity brl}})
      (record! conn {:external-id "cf-b"
                     :acquired-on #inst "2025-01-01"
                     :disposed-on #inst "2026-06-15"
                     :asset-class :br-renda-variavel-day
                     :proceeds {:amount 50000M :commodity brl}
                     :basis    {:amount 30000M :commodity brl}})
      (let [facts (run-provider
                   conn :individual p2026
                   {:inputs
                    {:capital-loss-carryforward
                     {:br-ganho-capital      200000M
                      :br-renda-variavel-day  5000M
                      :br-renda-variavel-long 99999M  ; lane not active, irrelevant
                      }}})
            a   (component-by-lane facts :br-ganho-capital)
            day (component-by-lane facts :br-renda-variavel-day)]
        (is (== 300000M (-> a :base :amount))
            "500k − 200k carry-in = 300k taxable")
        ;; 300k @ 15 % = 45 000
        (is (== 45000M (-> a :liability :amount)))
        (is (== 15000M (-> day :base :amount))
            "20k − 5k carry-in = 15k taxable")
        (is (== 3000M (-> day :liability :amount)))))))

(deftest carryforward-cross-lane-isolation
  (testing "a swing carry-in NEVER touches the day-trade lane"
    (let [conn (fresh)]
      (record! conn {:external-id "cl-day"
                     :acquired-on #inst "2026-05-15"
                     :disposed-on #inst "2026-05-15"
                     :asset-class :br-renda-variavel-day
                     :proceeds {:amount 30000M :commodity brl}
                     :basis    {:amount 10000M :commodity brl}})
      (let [facts (run-provider
                   conn :individual p2026
                   {:inputs {:capital-loss-carryforward
                             {:br-renda-variavel-long 999999M}}})
            day (component-by-lane facts :br-renda-variavel-day)]
        (is (== 20000M (-> day :base :amount))
            "huge swing carry doesn't bleed into the day lane")
        (is (== 4000M (-> day :liability :amount)))))))

;; ============================================================================
;; §8. Corporate Lucro Real — net cap gain folds to CIT base
;; ============================================================================

(deftest corporate-lucro-real-folds-to-cit-base
  (testing "PJ Lucro Real — gain folds into :cit-base-additions, zero own liability"
    (let [conn (fresh)]
      (record! conn {:external-id "pj-1"
                     :acquired-on #inst "2020-01-01"
                     :disposed-on #inst "2026-06-15"
                     :asset-class :br-real-estate-comercial
                     :proceeds {:amount 2000000M :commodity brl}
                     :basis    {:amount 1500000M :commodity brl}})
      (let [facts (run-provider conn :corporation p2026
                                {:tax-unit {:tax-regime :lucro-real}})
            cmp   (component-by-lane facts :br-corp-net-capital)]
        (is (some? cmp))
        (is (== 500000M (-> cmp :base :amount)))
        (is (== 0M (-> cmp :liability :amount))
            "the PJ component carries no own tax — it flows into IRPJ+CSLL")
        (is (= [500000M] (get-in cmp [:jurisdiction-specific-codes
                                      :cit-base-additions])))
        (is (= :lucro-real (get-in cmp [:jurisdiction-specific-codes :regime])))))))

;; ============================================================================
;; §9. Void exclusion — voided disposals don't reach the provider
;; ============================================================================

(deftest voided-disposals-excluded
  (testing "a voided disposal is dropped from the provider's source"
    (let [conn (fresh)]
      (record! conn {:external-id "vd-1"
                     :acquired-on #inst "2020-01-01"
                     :disposed-on #inst "2026-06-15"
                     :asset-class :br-real-estate-comercial
                     :proceeds {:amount 5000000M :commodity brl}
                     :basis    {:amount 1000000M :commodity brl}})
      (disposal/void! conn {:disposal "vd-1" :recorded-by-uid "u"})
      (let [facts (run-provider conn :individual p2026)]
        (is (empty? (:components facts))
            "voided → zero components")))))

;; ============================================================================
;; §10. Mixed-month worked example B
;; ============================================================================

(deftest mixed-b3-month-worked-example-b
  (testing "May 2026 — swing 18k exempt, day 12k @ 20 % = 2400 minus 120 IRRF"
    (let [conn (fresh)]
      ;; Swing ações: aggregate sale price R$ 18k → under R$ 20k → exempt
      (record! conn {:external-id "wb-swing"
                     :acquired-on #inst "2025-01-01"
                     :disposed-on #inst "2026-05-10"
                     :asset-class :br-renda-variavel-long
                     :proceeds {:amount 18000M :commodity brl}
                     :basis    {:amount 10000M :commodity brl}})
      ;; Day-trade mini-índice: R$ 12k gain
      (record! conn {:external-id "wb-day"
                     :acquired-on #inst "2026-05-15"
                     :disposed-on #inst "2026-05-15"
                     :asset-class :br-renda-variavel-day
                     :proceeds {:amount 18000M :commodity brl}
                     :basis    {:amount  6000M :commodity brl}})
      (let [facts (run-provider conn :individual p2026
                                {:inputs {:br-irrf-withheld
                                          {:swing 0.90M  ; 18000 × 0.005 %
                                           :day   120M    ; 12000 × 1 %
                                           }}})
            swing (component-by-lane facts :br-renda-variavel-long)
            day   (component-by-lane facts :br-renda-variavel-day)]
        ;; Swing: exempt
        (is (some? swing))
        (is (zero? (-> swing :base :amount)))
        ;; Day: gain 12k, tax 2400, prepaid 120, balance owes 2280
        (is (== 12000M (-> day :base :amount)))
        (is (== 2400M (-> day :liability :amount)))
        (is (== 120M (-> day :prepaid :amount)))
        ;; Combined DARF due (across all components)
        (let [bal (ptp/balance facts)]
          ;; total liability 0 + 2400 = 2400
          ;; total prepaid 0.90 + 120 = 120.90 → balance = 2279.10
          (is (== 2279.10M (:amount bal))))))))

(ns kontor.l10n-cn.lat-provider-test
  "Tests for the CN Land Appreciation Tax provider — note 133 §1.6 +
   §5.3."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.disposal :as disposal]
            [kontor.disposal.source :as disp-source]
            [kontor.l10n-cn.cgt-provider :as cn-cgt]
            [kontor.l10n-cn.cgt-statute :as cgt-statute]
            [kontor.l10n-cn.lat-provider :as lat]
            [kontor.period-tax-provider :as ptp]
            [kontor.tax-schedule :as ts]))

;; ============================================================================
;; Fixture
;; ============================================================================

(defn- fresh
  []
  (let [conn (core/create-test-db)]
    (disposal/install! conn)
    (cgt-statute/install! conn)
    (d/transact conn [{:kontor.commodity/symbol "CNY" :kontor.commodity/name "Chinese Yuan"
                       :kontor.commodity/precision 2}
                      {:entity/code "DEV" :entity/name "Developer"
                       :entity/kind :company :entity/country "CN"
                       :entity/functional-commodity [:kontor.commodity/symbol "CNY"]}])
    conn))

(def ^:private cny [:kontor.commodity/symbol "CNY"])
(def ^:private dev [:entity/code "DEV"])

(defn- record!
  [conn opts]
  (disposal/record-disposal!
   conn (merge {:entity          dev
                :kind            :sale
                :subject         cny
                :subject-kind    :real-estate-private
                :recorded-by-uid "test"
                :acquired-on     #inst "2022-01-01"
                :disposed-on     #inst "2026-06-15"
                :proceeds        {:amount 0M :commodity cny}
                :basis           {:amount 0M :commodity cny}}
               opts)))

(defn- dev-eid [conn]
  (d/q '[:find ?e . :where [?e :entity/code "DEV"]] (d/db conn)))

(defn- run-lat
  [conn period & [extra-ctx]]
  (let [source   (disp-source/datahike-source conn)
        provider (lat/cn-lat-provider {:source source})]
    (ptp/period-tax-facts
     provider
     (merge {:db (d/db conn)
             :entity (dev-eid conn)
             :period period}
            extra-ctx))))

(def ^:private p2026 {:from #inst "2026-01-01" :to #inst "2027-01-01"})

;; ============================================================================
;; §1. Plumbing — the schedule constructor
;; ============================================================================

(deftest lat-schedule-shape
  (testing "the schedule has 4 brackets, ascending boundaries, last :upper nil"
    (let [sched (lat/lat-schedule 1000000M)
          bs    (:brackets sched)]
      (is (= :progressive-bracket (:schedule/type sched)))
      (is (= 4 (count bs)))
      ;; Boundaries scaled by deductibles: 50% × 1M, 100% × 1M, 200% × 1M, nil.
      (is (= [500000M 1000000M 2000000M nil] (mapv :upper bs)))
      (is (= [0.30M 0.40M 0.50M 0.60M] (mapv :rate bs))))))

;; ============================================================================
;; §2. Each bracket — 30 / 40 / 50 / 60 %
;; ============================================================================

(deftest lat-bracket-1-thirty-percent
  (testing "ratio ≤ 50 % → flat 30 % marginal"
    (let [conn (fresh)]
      ;; Deductibles 10M, value-add 3M → ratio 30 % → entirely in bracket 1.
      (record! conn {:external-id "b1"
                     :asset-class :cn-developer-real-estate
                     :proceeds {:amount 13000000M :commodity cny}
                     :basis    {:amount 10000000M :commodity cny}})
      (let [facts (run-lat conn p2026)
            cmp   (first (:components facts))]
        (is (some? cmp))
        (is (== 3000000M (-> cmp :base :amount)))
        ;; 3,000,000 × 30 % = 900,000
        (is (== 900000M (-> cmp :liability :amount))
            "bracket-1 LAT: 30 % flat on 3M value-add")))))

(deftest lat-bracket-2-forty-percent
  (testing "ratio in 50-100 % → bracket 1 fully consumed + bracket-2 marginal"
    (let [conn (fresh)]
      ;; Deductibles 10M, value-add 7M → ratio 70 % → straddles 1 and 2.
      (record! conn {:external-id "b2"
                     :asset-class :cn-developer-real-estate
                     :proceeds {:amount 17000000M :commodity cny}
                     :basis    {:amount 10000000M :commodity cny}})
      (let [facts (run-lat conn p2026)
            cmp   (first (:components facts))]
        ;; First 5M (= 50% × 10M) @ 30 % = 1,500,000
        ;; Next  2M (7M − 5M)    @ 40 % =   800,000
        ;; Total                          2,300,000
        (is (== 2300000M (-> cmp :liability :amount))
            "bracket-2 LAT: 5M @ 30 % + 2M @ 40 % = 2.3M")))))

(deftest lat-bracket-3-fifty-percent
  (testing "ratio in 100-200 % → brackets 1+2 fully + bracket-3 marginal"
    (let [conn (fresh)]
      ;; Deductibles 10M, value-add 15M → ratio 150 % → straddles 1, 2, 3.
      (record! conn {:external-id "b3"
                     :asset-class :cn-developer-real-estate
                     :proceeds {:amount 25000000M :commodity cny}
                     :basis    {:amount 10000000M :commodity cny}})
      (let [facts (run-lat conn p2026)
            cmp   (first (:components facts))]
        ;; First  5M @ 30 % = 1,500,000
        ;; Next   5M @ 40 % = 2,000,000
        ;; Next   5M @ 50 % = 2,500,000  (15M − 10M = 5M in bracket 3)
        ;; Total            = 6,000,000
        (is (== 6000000M (-> cmp :liability :amount))
            "bracket-3 LAT: 5M @ 30 % + 5M @ 40 % + 5M @ 50 % = 6M")))))

(deftest lat-bracket-4-sixty-percent
  (testing "ratio > 200 % → brackets 1+2+3 fully + bracket-4 marginal"
    (let [conn (fresh)]
      ;; Deductibles 10M, value-add 30M → ratio 300 % → all four brackets fire.
      (record! conn {:external-id "b4"
                     :asset-class :cn-developer-real-estate
                     :proceeds {:amount 40000000M :commodity cny}
                     :basis    {:amount 10000000M :commodity cny}})
      (let [facts (run-lat conn p2026)
            cmp   (first (:components facts))]
        ;; First  5M @ 30 % = 1,500,000
        ;; Next   5M @ 40 % = 2,000,000
        ;; Next  10M @ 50 % = 5,000,000  (20M − 10M = 10M in bracket 3)
        ;; Next  10M @ 60 % = 6,000,000  (30M − 20M = 10M in bracket 4)
        ;; Total            = 14,500,000
        (is (== 14500000M (-> cmp :liability :amount))
            "bracket-4 LAT: 5M+5M+10M+10M @ 30/40/50/60 % = 14.5M")))))

;; ============================================================================
;; §3. Boundary crossings — exactly on a boundary
;; ============================================================================

(deftest lat-boundary-50-percent
  (testing "exactly at the 50 % boundary stays in bracket 1"
    (let [conn (fresh)]
      ;; Deductibles 10M, value-add 5M → ratio exactly 50 %.
      (record! conn {:external-id "boundary-50"
                     :asset-class :cn-developer-real-estate
                     :proceeds {:amount 15000000M :commodity cny}
                     :basis    {:amount 10000000M :commodity cny}})
      (let [facts (run-lat conn p2026)
            cmp   (first (:components facts))]
        ;; 5M @ 30 % = 1,500,000
        (is (== 1500000M (-> cmp :liability :amount)))))))

(deftest lat-boundary-100-percent
  (testing "exactly at the 100 % boundary just consumes bracket 1 + 2"
    (let [conn (fresh)]
      ;; Deductibles 10M, value-add 10M → ratio exactly 100 %.
      (record! conn {:external-id "boundary-100"
                     :asset-class :cn-developer-real-estate
                     :proceeds {:amount 20000000M :commodity cny}
                     :basis    {:amount 10000000M :commodity cny}})
      (let [facts (run-lat conn p2026)
            cmp   (first (:components facts))]
        ;; 5M @ 30 % + 5M @ 40 % = 1.5M + 2M = 3,500,000
        (is (== 3500000M (-> cmp :liability :amount)))))))

;; ============================================================================
;; §4. Carve-outs — personal residence exemption + ordinary residential
;; ============================================================================

(deftest lat-individual-residential-out-of-scope-by-construction
  (testing "Individual residential sales (Caishui [2008] 137) are out-of-scope for LAT — they ride :cn-residential and are picked up by the IIT provider, never recorded under :cn-developer-real-estate. Note 145 §1 P0-1."
    (let [conn (fresh)]
      ;; An individual sells a residence (held > 5 years, 满五唯一);
      ;; recorded with the substrate-correct :cn-residential class.
      (record! conn {:external-id "personal-1"
                     :asset-class :cn-residential
                     :acquired-on #inst "2018-01-01"
                     :disposed-on #inst "2026-06-15"
                     :residence?  true
                     :proceeds {:amount 30000000M :commodity cny}
                     :basis    {:amount  5000000M :commodity cny}})
      ;; (a) LAT sees nothing — :cn-residential is not LAT-eligible.
      (let [lat-facts (run-lat conn p2026)]
        (is (empty? (:components lat-facts))
            ":cn-residential is not LAT-eligible — only :cn-developer-real-estate is"))
      ;; (b) The IIT provider DOES route it — and with 满五唯一 fires the
      ;;     residential exemption (audit-line, zero liability).
      (let [source   (disp-source/datahike-source conn)
            iit      (cn-cgt/cn-iit-cgt-provider {:source source})
            iit-facts (ptp/period-tax-facts
                       iit {:db (d/db conn)
                            :entity (dev-eid conn)
                            :period p2026
                            :tax-unit {:tax-residency :resident-individual
                                       :family-sole-residence? true}})
            cmp (first (:components iit-facts))]
        (is (some? cmp) "IIT provider produces a component")
        (is (== 0M (-> cmp :liability :amount))
            "满五唯一 exemption → zero IIT liability; audit-trail surfaces on IIT side")))))

(deftest lat-ordinary-residential-under-20pct-exempt
  (testing "developer ordinary residential with value-add ≤ 20 % exempt (Provisional Regs §8 §1)"
    (let [conn (fresh)]
      ;; Deductibles 10M, value-add 1.5M → ratio 15 % → exempt.
      (record! conn {:external-id "ord-res-1"
                     :asset-class :cn-developer-real-estate
                     :proceeds {:amount 11500000M :commodity cny}
                     :basis    {:amount 10000000M :commodity cny}})
      (let [facts (run-lat conn p2026
                           {:tax-unit {:developer? true
                                       :ordinary-residential? true}})]
        (is (empty? (:components facts)))))))

(deftest lat-non-developer-real-estate-not-eligible
  (testing "non-developer real-estate (eg :cn-residential) NOT handled by LAT"
    (let [conn (fresh)]
      (record! conn {:external-id "non-dev-1"
                     :asset-class :cn-residential
                     :proceeds {:amount 30000000M :commodity cny}
                     :basis    {:amount  5000000M :commodity cny}})
      (let [facts (run-lat conn p2026)]
        (is (empty? (:components facts))
            "only :cn-developer-real-estate is LAT-eligible")))))

;; ============================================================================
;; §5. Loss case — value-add ≤ 0 produces no LAT
;; ============================================================================

(deftest lat-no-liability-on-loss
  (testing "proceeds ≤ basis (no appreciation) → zero LAT"
    (let [conn (fresh)]
      (record! conn {:external-id "loss-1"
                     :asset-class :cn-developer-real-estate
                     :proceeds {:amount 8000000M :commodity cny}
                     :basis    {:amount 10000000M :commodity cny}})
      (let [facts (run-lat conn p2026)
            cmp   (first (:components facts))]
        ;; A component IS produced (the disposal is LAT-eligible), but
        ;; the liability is zero.
        (is (some? cmp))
        (is (== 0M (-> cmp :liability :amount)))))))

(ns kontor.l10n-mx.investment-income-provider-test
  "Tests for the MX investment-income provider (research note 157).

   Coverage:
     §1  PF dividend acumulable lane — gross-up 1.4286 + 30 % factor-credit.
     §2  PF dividend Adicional — 10 % definitive on post-2014 CUFIN slice.
     §3  CUFIN-paid (pre-2014) dividend — Adicional suppressed.
     §4  Bank interest pre-2026 — 0.50 % WHT on daily-avg balance.
     §5  Bank interest from 2026-01-01 — 0.90 % WHT (bitemporal cliff).
     §6  Real interest = nominal − INPC inflation adjustment.
     §7  Foreign-source dividend — gross to PIT + §5 FTC capped.
     §8  PJ-to-PJ CUFIN dividend — exempt audit-only component.
     §9  PJ taxable dividends + interest fold into CIT base.
     §10 Mixed PF portfolio — Sr López worked example (note 157 §2 Ex A,
         dividend + interest legs).
     §11 Kind validation — provider rejects invalid :kind.
     §12 Components carry :investment-income-tax :kind."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.l10n-mx.investment-income-provider :as inv]
            [kontor.l10n-mx.investment-income-statute :as inv-statute]
            [kontor.period-tax-provider :as ptp]))

;; ============================================================================
;; Fixture
;; ============================================================================

(defn- fresh
  "Fresh DB with the MX investment-income statute installed + an MXN
   commodity."
  []
  (let [conn (core/create-test-db)]
    (inv-statute/install! conn)
    (d/transact conn [{:commodity/symbol "MXN" :commodity/name "Mexican Peso"
                       :commodity/precision 2}])
    conn))

(def ^:private p2026   {:from #inst "2026-01-01" :to #inst "2027-01-01"})
(def ^:private as-2026 #inst "2026-06-30")
(def ^:private p2025   {:from #inst "2025-01-01" :to #inst "2026-01-01"})
(def ^:private as-2025 #inst "2025-06-30")

(defn- run-individual
  [conn items & [extra-ctx]]
  (let [provider (inv/mx-individual-investment-income-provider {})]
    (ptp/period-tax-facts
     provider
     (merge {:db     (d/db conn)
             :entity :tp
             :period p2026
             :as-of  as-2026
             :inputs {:mx-investment-income items}}
            extra-ctx))))

(defn- run-corporate
  [conn items & [extra-ctx]]
  (let [provider (inv/mx-corporate-investment-income-provider {})]
    (ptp/period-tax-facts
     provider
     (merge {:db     (d/db conn)
             :entity :corp
             :period p2026
             :as-of  as-2026
             :inputs {:mx-investment-income items}}
            extra-ctx))))

(defn- component-by-lane [facts lane]
  (->> (:components facts)
       (filter #(= lane (get-in % [:jurisdiction-specific-codes :lane])))
       first))

(defn- credit-by-code [component code]
  (->> (:credits component)
       (filter #(= code (:code %)))
       first))

;; ============================================================================
;; §1. PF dividend acumulable — gross-up 1.4286 + 30 % factor-credit
;; ============================================================================

(deftest pf-dividend-acumulable-gross-up-and-factor-credit
  (testing "MXN 100,000 dividend → grossed-up 142,860; factor-credit 42,858"
    (let [conn  (fresh)
          facts (run-individual conn {:dividends [{:source-id :corp-a
                                                   :amount    100000M}]})
          acum  (component-by-lane facts :mx-pf-dividend-acumulable)]
      (is (some? acum))
      ;; 100000 × 1.4286 = 142860
      (is (== 142860M (-> acum :base :amount)))
      ;; pit-base-additions carries the grossed-up amount
      (is (= [142860M] (get-in acum [:jurisdiction-specific-codes :pit-base-additions])))
      ;; Factor credit = 142860 × 0.30 = 42858
      (let [credit (credit-by-code acum :mx-corporate-isr-proxy)]
        (is (some? credit))
        (is (== 42858M (:amount credit)))
        (is (false? (:refundable? credit))))
      ;; The acumulable component owes nothing on its own — PIT does the math
      (is (== 0M (-> acum :liability :amount))))))

;; ============================================================================
;; §2. PF dividend Adicional — 10 % definitive on post-2014 CUFIN
;; ============================================================================

(deftest pf-dividend-adicional-10-pct
  (testing "MXN 200,000 post-2014 CUFIN dividend → Adicional 20,000; SCJN-confirmed"
    (let [conn  (fresh)
          facts (run-individual conn
                                {:dividends [{:source-id :corp-a
                                              :amount    200000M
                                              :adicional-withheld 20000M}]})
          adic  (component-by-lane facts :mx-pf-dividend-adicional)]
      (is (some? adic))
      ;; 200000 × 0.10 = 20000
      (is (== 200000M (-> adic :base :amount)))
      (is (== 20000M  (-> adic :gross-liability :amount)))
      (is (== 20000M  (-> adic :liability :amount)))
      ;; Payer-side WHT recorded
      (is (== 20000M (-> adic :prepaid :amount)))
      ;; Audit hint
      (is (true? (get-in adic [:jurisdiction-specific-codes :mx/scjn-confirmed-2026?])))
      ;; Default CUFIN bucket = post-2014 (the law's presumption)
      (is (= :post-2014 (get-in adic [:jurisdiction-specific-codes :cufin-bucket]))))))

;; ============================================================================
;; §3. CUFIN-paid (pre-2014) dividend — Adicional SUPPRESSED
;; ============================================================================

(deftest pf-dividend-pre-2014-cufin-adicional-suppressed
  (testing "Dividend marked :elective-regime #{:mx-cufin-paid} → no Adicional component"
    (let [conn  (fresh)
          facts (run-individual conn
                                {:dividends
                                 [{:source-id        :corp-a
                                   :amount           300000M
                                   :cufin-bucket     :pre-2014
                                   :elective-regime  #{:mx-cufin-paid}}]})
          acum  (component-by-lane facts :mx-pf-dividend-acumulable)
          adic  (component-by-lane facts :mx-pf-dividend-adicional)]
      ;; acumulable lane still fires — pre-2014 CUFIN dividends still
      ;; gross-up + factor-credit (just no 10 % topping)
      (is (some? acum))
      (is (== 428580M (-> acum :base :amount)))
      ;; The Adicional component is NOT present
      (is (nil? adic))
      ;; Pre-2014 bucket recorded
      (is (= :pre-2014 (get-in acum [:jurisdiction-specific-codes :cufin-bucket]))))))

;; ============================================================================
;; §4. Bank interest 2025 — 0.50 % provisional WHT on daily-avg balance
;; ============================================================================

(deftest bank-interest-pre-2026-rate
  (testing "Daily-avg balance 1,000,000 in FY2025 → WHT 0.50 % × 1M = 5,000"
    (let [conn  (fresh)
          facts (run-individual conn
                                {:interest [{:source-id          :bbva
                                             :nominal-interest   60000M
                                             :daily-avg-balance  1000000M}]}
                                {:as-of as-2025 :period p2025})
          intr  (component-by-lane facts :mx-pf-bank-interest)
          wh    (credit-by-code intr :mx-bank-interest-provisional-wh)]
      (is (some? intr))
      (is (== 0.0050M (get-in intr [:jurisdiction-specific-codes :mx/wht-rate])))
      ;; 1,000,000 × 0.005 = 5,000
      (is (some? wh))
      (is (== 5000M (:amount wh)))
      ;; The credit is refundable (provisional, may exceed annual obligation)
      (is (true? (:refundable? wh))))))

;; ============================================================================
;; §5. Bank interest from 2026-01-01 — 0.90 % bitemporal cliff (LIF 2026)
;; ============================================================================

(deftest bank-interest-2026-rate-cliff
  (testing "Same 1M daily-avg in FY2026 → WHT 0.90 % × 1M = 9,000 (LIF 2026 hike)"
    (let [conn  (fresh)
          facts (run-individual conn
                                {:interest [{:source-id          :bbva
                                             :nominal-interest   80000M
                                             :daily-avg-balance  1000000M}]})
          intr  (component-by-lane facts :mx-pf-bank-interest)
          wh    (credit-by-code intr :mx-bank-interest-provisional-wh)]
      (is (== 0.0090M (get-in intr [:jurisdiction-specific-codes :mx/wht-rate])))
      ;; 1,000,000 × 0.009 = 9,000
      (is (== 9000M (:amount wh)))))

  (testing "Note 157 §2 Ex A: 2M daily-avg in 2026 → WHT = 18,000"
    (let [conn  (fresh)
          facts (run-individual conn
                                {:interest [{:source-id          :bbva
                                             :nominal-interest   80000M
                                             :daily-avg-balance  2000000M}]})
          intr  (component-by-lane facts :mx-pf-bank-interest)
          wh    (credit-by-code intr :mx-bank-interest-provisional-wh)]
      (is (== 18000M (:amount wh))))))

;; ============================================================================
;; §6. Real interest = nominal − INPC inflation adjustment
;; ============================================================================

(deftest bank-interest-real-via-inpc
  (testing "Nominal 80,000 − inflation (2M × 0.03) 60,000 = real 20,000"
    (let [conn  (fresh)
          facts (run-individual conn
                                {:interest [{:source-id          :bbva
                                             :nominal-interest   80000M
                                             :daily-avg-balance  2000000M
                                             :inpc-factor        1.03M}]})
          intr  (component-by-lane facts :mx-pf-bank-interest)]
      ;; real interest = 80,000 − (2,000,000 × 0.03) = 20,000
      (is (== 20000M (-> intr :base :amount)))
      (is (= [20000M] (get-in intr [:jurisdiction-specific-codes :pit-base-additions])))))

  (testing "Consumer-supplied :real-interest wins over INPC math"
    (let [conn  (fresh)
          facts (run-individual conn
                                {:interest [{:source-id          :bbva
                                             :nominal-interest   80000M
                                             :real-interest      15000M
                                             :daily-avg-balance  2000000M
                                             :inpc-factor        1.03M}]})
          intr  (component-by-lane facts :mx-pf-bank-interest)]
      (is (== 15000M (-> intr :base :amount))))))

;; ============================================================================
;; §7. Foreign-source dividend — gross to PIT + FTC capped
;; ============================================================================

(deftest pf-foreign-dividend-with-ftc
  (testing "$2,000 MSFT dividend + $300 US WHT → gross to PIT base; FTC 300"
    (let [conn  (fresh)
          facts (run-individual conn
                                {:dividends [{:source-id :msft
                                              :amount    2000M
                                              :foreign?  true
                                              :country   :us}]
                                 :foreign-tax-credits
                                 {:msft {:country :us :income 2000M :paid 300M}}})
          fd    (component-by-lane facts :mx-pf-foreign-dividend)
          ftc   (credit-by-code fd :mx-foreign-tax-credit)]
      (is (some? fd))
      ;; No MX gross-up on foreign dividend — base = 2000
      (is (== 2000M (-> fd :base :amount)))
      (is (= [2000M] (get-in fd [:jurisdiction-specific-codes :pit-base-additions])))
      ;; FTC = min(paid 300, cap = income × 1.0 = 2000) = 300
      (is (some? ftc))
      (is (== 300M (:amount ftc)))
      ;; No carryforward when paid ≤ cap
      (is (== 0M (get-in fd [:jurisdiction-specific-codes :mx/ftc-carryforward]))))))

;; ============================================================================
;; §8. PJ-to-PJ CUFIN dividend — exempt audit-only component
;; ============================================================================

(deftest corp-pj-to-pj-cufin-exempt
  (testing "ParentCo receives 10M from SubCo CUFIN → exempt + CUFIN credit recorded"
    (let [conn  (fresh)
          facts (run-corporate conn
                               {:dividends [{:source-id       :subco
                                             :amount          10000000M
                                             :cufin-bucket    :post-2014
                                             :elective-regime #{:mx-cufin-paid}}]})
          exempt (component-by-lane facts :mx-pj-pj-dividend-exempt)
          feeder (component-by-lane facts :mx-pm-cit-feeder)]
      (is (some? exempt))
      ;; Zero tax — pure audit
      (is (== 0M (-> exempt :liability :amount)))
      ;; CUFIN credit recorded (post-tax pool transferred up)
      (is (== 10000000M (get-in exempt [:jurisdiction-specific-codes :mx/cufin-credit-in])))
      ;; No CIT feeder (the only dividend is exempt)
      (is (nil? feeder)))))

;; ============================================================================
;; §9. PJ non-exempt dividends + interest fold into CIT base
;; ============================================================================

(deftest corp-non-cufin-divs-and-interest-fold-to-cit
  (testing "Corp receives 500k non-CUFIN div + 100k interest → CIT feeder 600k"
    (let [conn  (fresh)
          facts (run-corporate conn
                               {:dividends [{:source-id    :outsider
                                             :amount       500000M
                                             :cufin-bucket :post-2014}]
                                :interest  [{:source-id        :bond
                                             :nominal-interest 100000M}]})
          feeder (component-by-lane facts :mx-pm-cit-feeder)]
      (is (some? feeder))
      (is (== 600000M (-> feeder :base :amount)))
      (is (= [600000M] (get-in feeder [:jurisdiction-specific-codes :cit-base-additions]))))))

;; ============================================================================
;; §10. Mixed PF — Sr López worked example (note 157 §2 Example A)
;; ============================================================================

(deftest sr-lopez-mixed-dividend-and-interest
  (testing "200k post-2014 CUFIN div (gross-up 285,720; credit 85,716; Adicional 20k)
            + 80k nominal interest (2M daily-avg × 0.009 = 18k WHT, INPC 3 % → real 20k)"
    (let [conn  (fresh)
          facts (run-individual conn
                                {:dividends [{:source-id          :corp-a
                                              :amount             200000M
                                              :adicional-withheld 20000M}]
                                 :interest  [{:source-id          :bbva
                                              :nominal-interest   80000M
                                              :daily-avg-balance  2000000M
                                              :inpc-factor        1.03M}]})
          acum  (component-by-lane facts :mx-pf-dividend-acumulable)
          adic  (component-by-lane facts :mx-pf-dividend-adicional)
          intr  (component-by-lane facts :mx-pf-bank-interest)]
      ;; All three lanes present
      (is (some? acum))
      (is (some? adic))
      (is (some? intr))

      (testing "dividend acumulable: gross-up 285,720; factor-credit 85,716"
        (is (== 285720M (-> acum :base :amount)))
        (is (== 85716M  (:amount (credit-by-code acum :mx-corporate-isr-proxy)))))

      (testing "dividend Adicional: 200k × 0.10 = 20,000"
        (is (== 20000M (-> adic :liability :amount))))

      (testing "real interest = 80,000 − 60,000 = 20,000; WHT 18,000"
        (is (== 20000M (-> intr :base :amount)))
        (is (== 18000M (:amount (credit-by-code intr :mx-bank-interest-provisional-wh)))))

      (testing "PIT base additions across all three lanes"
        ;; acumulable contributes 285,720; interest contributes 20,000
        (let [acum-add (get-in acum [:jurisdiction-specific-codes :pit-base-additions])
              intr-add (get-in intr [:jurisdiction-specific-codes :pit-base-additions])]
          (is (= [285720M] acum-add))
          (is (= [20000M]  intr-add)))))))

;; ============================================================================
;; §11. Kind validation
;; ============================================================================

(deftest invalid-kind-rejected
  (testing "non-:individual/:corporation :kind throws"
    (let [conn     (fresh)
          provider (assoc (inv/mx-individual-investment-income-provider {})
                          :kind :bogus)]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #":kind must be :individual or :corporation"
           (ptp/period-tax-facts
            provider
            {:db (d/db conn) :entity :tp :period p2026
             :inputs {:mx-investment-income {}}}))))))

;; ============================================================================
;; §12. Components carry the correct :investment-income-tax :kind
;; ============================================================================

(deftest components-carry-investment-income-tax-kind
  (testing "individual: every component is :investment-income-tax"
    (let [conn  (fresh)
          facts (run-individual conn
                                {:dividends [{:source-id :corp-a :amount 100000M}]
                                 :interest  [{:source-id :bbva :nominal-interest 5000M
                                              :daily-avg-balance 100000M}]})]
      (is (seq (:components facts)))
      (is (every? #(= :investment-income-tax (:kind %)) (:components facts)))))

  (testing "corporate: every component is :investment-income-tax"
    (let [conn  (fresh)
          facts (run-corporate conn
                               {:dividends [{:source-id    :outsider
                                             :amount       100000M}]
                                :interest  [{:source-id        :bond
                                             :nominal-interest 50000M}]})]
      (is (seq (:components facts)))
      (is (every? #(= :investment-income-tax (:kind %)) (:components facts))))))

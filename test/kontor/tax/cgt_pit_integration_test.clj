(ns kontor.tax.cgt-pit-integration-test
  "Showcase 08 — end-to-end CGT → PIT composition (ADR-103 integration).

   Scenario: **Sarah Chen** — single US filer, tax year 2026.
     - W-2 wages: $180,000 (booked as ordinary income to the GL).
     - LT stock sale: NVDA 100 sh, acquired 2024-03-01, disposed
       2026-08-15. Proceeds $50,000, basis $25,000 → $25,000 LT gain.
     - ST stock sale: TSLA 200 sh, acquired 2026-03-15, disposed
       2026-09-01. Proceeds $30,000, basis $22,000 → $8,000 ST gain.
     - Rental property sale: proceeds $500,000, basis $200,000,
       depreciation-taken $80,000 → $300,000 gain; the depreciation
       portion is §1250-unrecaptured at the 25 % cap rate.
     - High MAGI ($500k) → NIIT 3.8 % surtax on net investment income.

   Validates THE composition seam that's documented in ADR-103 §A.5
   but never end-to-end tested: CGT provider's `:jurisdiction-specific
   -codes :pit-base-additions` slice rides through `:base-transform
   :adjustments :additions` into the PIT provider.

   Per Phase A3 of the post-CGT-sweep plan."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.book :as book]
            [kontor.tax.cgt :as cgt]
            [kontor.core :as core]
            [kontor.disposal :as disposal]
            [kontor.disposal.provider :as disp-provider]
            [kontor.l10n-us.cgt-provider :as us-cgt]
            [kontor.l10n-us.cgt-statute :as cgt-statute]
            [kontor.l10n-us.pit-provider :as us-pit]
            [kontor.l10n-us.pit-statute :as pit-statute]
            [kontor.money :as money]
            [kontor.reporting.report :as report]
            [kontor.tax.period-tax-provider :as ptp]))

;; ============================================================================
;; Fixture — a US individual entity, USD commodity, all schemas installed
;; ============================================================================

(def ^:private usd [:kontor.commodity/symbol "USD"])

(defn- fresh []
  (let [conn (core/create-test-db)]
    (disposal/install! conn)
    (cgt-statute/install! conn)
    (pit-statute/install! conn)
    (d/transact conn
                [{:kontor.commodity/symbol "USD" :kontor.commodity/name "US Dollar"
                  :kontor.commodity/precision 2}
                 ;; Entity for Sarah.
                 {:kontor.entity/code "SARAH" :kontor.entity/name "Sarah Chen (Individual)"
                  :kontor.entity/kind :company :kontor.entity/country "US"
                  :kontor.entity/functional-commodity usd}
                 ;; Journals: GEN for wages, SALE for proceeds (not used —
                 ;; book.sell! infers the journal automatically).
                 {:kontor.journal/code "GEN"  :kontor.journal/type :general :kontor.journal/active true}
                 {:kontor.journal/code "SALE" :kontor.journal/type :sale    :kontor.journal/active true}
                 ;; Account skeleton (minimal for Form 1040 + cap gains story).
                 {:kontor.account/path "Income:Wages-W2"       :kontor.account/type :income
                  :kontor.account/commodity usd}
                 {:kontor.account/path "Assets:Bank"           :kontor.account/type :asset
                  :kontor.account/commodity usd}
                 {:kontor.account/path "Assets:Brokerage"      :kontor.account/type :asset
                  :kontor.account/commodity usd}
                 {:kontor.account/path "Income:Capital-Gains"  :kontor.account/type :income
                  :kontor.account/commodity usd}
                 {:kontor.account/path "Expenses:Income-Tax"   :kontor.account/type :expense
                  :kontor.account/commodity usd}
                 {:kontor.account/path "Liabilities:Tax-Payable" :kontor.account/type :liability
                  :kontor.account/commodity usd}])
    conn))

(defn- holdco-eid [conn entity-code]
  (d/q '[:find ?e . :in $ ?c :where [?e :kontor.entity/code ?c]]
       (d/db conn) entity-code))

;; ============================================================================
;; Story: Sarah's 2026 tax year
;; ============================================================================

(def ^:private fy-2026 {:from #inst "2026-01-01" :to #inst "2027-01-01"})
(def ^:private sarah [:kontor.entity/code "SARAH"])

(defn- book-wages! [conn]
  ;; W-2 wages: $180,000. Posted as a single year-end debit Bank /
  ;; credit Income:Wages-W2 — the kontor.book sell! verb is a
  ;; convenient way to book income (despite the verb name; sell! is
  ;; just "credit income, debit something").
  (book/sell! conn {:debit-account  [:kontor.account/path "Assets:Bank"]
                    :credit-account [:kontor.account/path "Income:Wages-W2"]
                    :amount         180000M
                    :commodity      usd
                    :effective-date #inst "2026-12-31"}))

(defn- record-disposals! [conn]
  ;; LT stock — NVDA, held >1 year.
  (disposal/record-disposal!
   conn {:entity          sarah
         :external-id     "nvda-lt"
         :kind            :sale
         :subject         usd                 ; stand-in ref
         :subject-kind    :securities-stock
         :acquired-on     #inst "2024-03-01"
         :disposed-on     #inst "2026-08-15"
         :proceeds        {:amount 50000M :commodity usd}
         :basis           {:amount 25000M :commodity usd}
         :recorded-by-uid "sarah"})
  ;; ST stock — TSLA, held <1 year.
  (disposal/record-disposal!
   conn {:entity          sarah
         :external-id     "tsla-st"
         :kind            :sale
         :subject         usd
         :subject-kind    :securities-stock
         :acquired-on     #inst "2026-03-15"
         :disposed-on     #inst "2026-09-01"
         :proceeds        {:amount 30000M :commodity usd}
         :basis           {:amount 22000M :commodity usd}
         :recorded-by-uid "sarah"})
  ;; Rental property with depreciation — §1250 unrecaptured at 25%.
  (disposal/record-disposal!
   conn {:entity            sarah
         :external-id       "rental-prop"
         :kind              :sale
         :subject           usd
         :subject-kind      :fixed-asset
         :asset-class       :us-real-property
         :acquired-on       #inst "2010-06-01"
         :disposed-on       #inst "2026-11-30"
         :proceeds          {:amount 500000M :commodity usd}
         :basis             {:amount 200000M :commodity usd}
         :depreciation-taken {:amount 80000M :commodity usd}
         :recorded-by-uid   "sarah"}))

;; ============================================================================
;; Helpers — provider composition
;; ============================================================================

(defn- run-cgt [conn]
  (let [source   (disp-provider/datahike-provider conn)
        provider (us-cgt/us-individual-cgt-provider {:source source})]
    (ptp/period-tax-facts
     provider {:db       (d/db conn)
               :entity   (holdco-eid conn "SARAH")
               :period   fy-2026
               :tax-unit {:filing-status :single}
               :inputs   {:net-investment-income 33000M  ; LT 25k + ST 8k
                          :magi 500000M}})))

(defn- compose-pit-inputs
  "THE COMPOSITION SEAM (ADR-103 §A.5). Delegates to
   `kontor.tax.cgt/fold-into-base-transform` to read the
   `:pit-base-additions` / `:pit-base-deductions` slice across CGT
   components. Post Gap #5 US slice the new
   substrate-aware PIT provider consumes a scalar `:cgt-pit-base-
   additions` (sum of `:pit-base-additions`) — distinct from the
   legacy `:base-transform :additions` shape (which the pre-ADR-101
   PersonalIncomeTaxProvider consumed). The helper returns the SCALAR
   sum, or 0M when nothing to fold."
  ^java.math.BigDecimal [cgt-facts]
  (let [bt (cgt/fold-into-base-transform cgt-facts :pit)]
    (reduce + 0M (get bt :additions []))))

(defn- gross-income-from-gl
  "Marginalize gross income from the GL for the period — was the
   responsibility of the legacy record-shape PIT provider's
   `gross-income` fn; the new substrate-aware provider expects the
   consumer to pre-compute this. We mirror the legacy marginalization
   here so the integration test still exercises the GL→PIT path."
  ^java.math.BigDecimal [conn]
  (let [postings (report/report-postings
                  conn {:from (:from fy-2026) :to (:to fy-2026)})
        by-type  (report/marginalize postings :account-type
                                     {:sign :inflow :commodity usd})]
    (or (some-> (get-in by-type [:income :value]) :amount) 0M)))

(defn- run-pit [conn cgt-pit-base-additions-scalar]
  ;; NOTE: we omit `:entity` here because `book/sell!` does not tag
  ;; postings with `:kontor.posting/entity` (that's an ADR-031 multi-entity
  ;; mode concern). In single-entity mode the PIT provider aggregates
  ;; every income posting in the period.
  (let [provider (us-pit/us-pit-provider {})
        gross    (gross-income-from-gl conn)]
    (ptp/period-tax-facts
     provider {:db       (d/db conn)
               :period   fy-2026
               :as-of    #inst "2026-12-31"
               :tax-unit {:filing-status :single}
               :inputs   (cond-> {:gross-income gross}
                           (and (some? cgt-pit-base-additions-scalar)
                                (pos? cgt-pit-base-additions-scalar))
                           (assoc :cgt-pit-base-additions cgt-pit-base-additions-scalar))})))

(defn- find-cgt-component [cgt-facts lane]
  (->> (:components cgt-facts)
       (filter #(= lane (get-in % [:jurisdiction-specific-codes :lane])))
       first))

;; ============================================================================
;; Tests
;; ============================================================================

(deftest cgt-emits-four-components
  (testing "Sarah's 4 lanes: ST → PIT base, LT → own §1(h), §1250 → 25%, NIIT"
    (let [conn (fresh)]
      (book-wages! conn)
      (record-disposals! conn)
      (let [facts (run-cgt conn)
            st    (find-cgt-component facts :st)
            lt    (find-cgt-component facts :lt)
            §1250 (find-cgt-component facts :§1250-unrecaptured)
            niit  (find-cgt-component facts :niit)]
        (is (some? st)    "ST component present")
        (is (some? lt)    "LT component present")
        (is (some? §1250) "§1250-unrecaptured component present")
        (is (some? niit)  "NIIT surtax component present")

        ;; ST: $8k flows to PIT base, no own liability
        (is (== 8000M (-> st :base :amount)))
        (is (== 0M    (-> st :liability :amount)))
        (is (= [8000M] (get-in st [:jurisdiction-specific-codes
                                   :pit-base-additions])))

        ;; LT base = NVDA $25k + rental residual ($300k − $80k §1250
        ;; unrecaptured = $220k) = $245k. Per IRC §1(h)(6)(A) the
        ;; depreciation-excess residual joins the regular LT lane.
        ;; Single filer 2026 brackets (Rev. Proc. 2025-32):
        ;;   0 % to $49,450, 15 % to $545,500, 20 % above.
        ;; Tax = 0 % × $49,450 + 15 % × ($245,000 − $49,450)
        ;;     = 0.15 × $195,550 = $29,332.50.
        (is (== 245000M (-> lt :base :amount))
            "LT = NVDA $25k + rental residual ($300k − $80k §1250) $220k")
        (is (== 29332.5M (-> lt :liability :amount)))

        ;; §1250: only the depreciation slice. base = min($300k gain,
        ;; $80k dep-taken) = $80k. Tax = $80k × 25% = $20,000.
        (is (== 80000M (-> §1250 :base :amount))
            "§1250 base = min(gain, dep-taken) per §1(h)(6)(A)")
        (is (== 20000M (-> §1250 :liability :amount)))

        ;; NIIT: 3.8% on NII = min(NII, MAGI excess over threshold).
        ;; NII = 33k (the test-supplied 25k + 8k), MAGI = 500k, threshold
        ;; single = 200k → excess = 300k. min(33k, 300k) = 33k.
        ;; Tax = 33k × 3.8% = 1,254.
        (is (== 1254M (-> niit :liability :amount)))))))

(deftest pit-consumes-cgt-pit-base-additions
  (testing "ST gain + (here none) ordinary recapture compose into PIT base"
    (let [conn (fresh)]
      (book-wages! conn)
      (record-disposals! conn)
      (let [cgt-facts   (run-cgt conn)
            base-add    (compose-pit-inputs cgt-facts)
            pit-facts   (run-pit conn base-add)
            [pit-comp]  (:components pit-facts)]
        ;; Base-additions should carry the ST $8k slice (scalar sum
        ;; under the new substrate-aware contract — research note 188).
        (is (== 8000M base-add)
            "compose-pit-inputs gathered the :pit-base-additions slice")

        ;; PIT taxable base = gross income (W-2 $180k) + CGT additions ($8k)
        ;; − standard deduction (single TY 2025 = $15,000 from
        ;; US.PIT.§63.standard-deduction-single).
        ;; Don't pin the exact number — assert the relationship.
        (let [taxable (-> pit-comp :base :amount)]
          (is (> taxable 170000M)
              "taxable base > $170k after standard deduction off $188k gross")
          (is (< taxable 190000M)
              "but < $190k"))

        ;; Liability should be positive and clearly larger than what
        ;; W-2 income alone would yield.
        (let [tax (-> pit-comp :liability :amount)]
          (is (pos? tax))
          (is (> tax 30000M) "single filer on ~$173k taxable — well above $30k")
          (is (< tax 60000M) "but well below $60k"))))))

(deftest full-tax-stack-sums-correctly
  (testing "Total tax = PIT (incl. CGT-ST fold) + LT-CGT + §1250 + NIIT"
    (let [conn (fresh)]
      (book-wages! conn)
      (record-disposals! conn)
      (let [cgt-facts (run-cgt conn)
            pit-facts (run-pit conn (compose-pit-inputs cgt-facts))
            pit-tax   (-> pit-facts :components first :liability :amount)
            cgt-tax   (reduce + 0M
                              (map #(-> % :liability :amount)
                                   (:components cgt-facts)))
            total     (+ pit-tax cgt-tax)]
        ;; Each lane contributes (corrected per §1(h)(6)(A)):
        ;;   PIT  (180k W-2 + 8k ST = 188k − std-ded ~$14.6k; 2024
        ;;         single brackets ≈ $30k-$35k)
        ;;   LT   $29,332.50 (15 % × $195,550 above $49,450 cusp)
        ;;   §1250 $20,000 (25 % × min(gain, dep) = $80k)
        ;;   NIIT  $1,254 (3.8 % × NII $33k)
        ;; Sum ≈ $80k-$85k.
        (is (> total 60000M))
        (is (< total 100000M))

        (let [non-pit (+ (-> (find-cgt-component cgt-facts :§1250-unrecaptured)
                             :liability :amount)
                         (-> (find-cgt-component cgt-facts :lt)
                             :liability :amount)
                         (-> (find-cgt-component cgt-facts :niit)
                             :liability :amount))]
          (is (== 50586.5M non-pit)
              "§1250 $20k + LT $29,332.50 + NIIT $1,254 = $50,586.50"))))))

(deftest voided-disposal-removed-from-cgt-stack
  (testing "void! a recorded disposal → it no longer reaches the CGT provider"
    (let [conn (fresh)]
      (book-wages! conn)
      (record-disposals! conn)
      (disposal/void! conn {:disposal "rental-prop" :recorded-by-uid "sarah"})
      (let [facts (run-cgt conn)
            §1250 (find-cgt-component facts :§1250-unrecaptured)]
        (is (nil? §1250)
            "voided rental → §1250 component disappears")))))

(ns kontor.l10n-au.investment-income-provider-test
  "Tests for the AU investment-income provider (research note 153).

   Coverage:
     §1  Marcus — resident individual, fully franked $700 @ 30 % →
         franking credit $300 refundable, gross-up $1,000 (note 153 §2.1).
     §2  Murphy SMSF pension phase — same dividend → full $300 refunded.
     §3  Murphy SMSF accumulation phase — refundable too (15 % fund tax
         downstream).
     §4  Non-fixed trust (no FTE) — franking credit LOST.
     §5  Fixed trust — passthrough (refundable).
     §6  :discretionary-fte raises :not-yet-implemented.
     §7  Sarah day-trader — 18 days held, but small-shareholder exemption
         (≤ $5k FC) FIRES → credit usable.
     §8  Same but YTD already at $5k → exemption FAILS → credit denied
         under 45-day rule.
     §9  BRE 25 % distributor — different gross-up math.
     §10 Foreign-source dividend with FITO — non-refundable.
     §11 Resident interest with TFN supplied — folds to PIT base; no
         credit fires.
     §12 Resident interest with no TFN — 47 % withheld → refundable.
     §13 Resident company — gross-up + non-refundable credit + franking-
         account-credit-pending signal.
     §14 Foreign resident — franking lost; no FITO for foreign source.
     §15 Kind validation."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.l10n-au.investment-income-provider :as inv]
            [kontor.l10n-au.investment-income-statute :as inv-statute]
            [kontor.period-tax-provider :as ptp]))

;; ============================================================================
;; Fixture
;; ============================================================================

(defn- fresh
  "Fresh test DB with the AU investment-income statute installed + an
   AUD commodity."
  []
  (let [conn (core/create-test-db)]
    (inv-statute/install! conn)
    (d/transact conn [{:kontor.commodity/symbol "AUD" :kontor.commodity/name "Australian Dollar"
                       :kontor.commodity/precision 2}])
    conn))

(def ^:private p2026
  "An AU FY 2025-26 period (1 Jul 2025 → 30 Jun 2026)."
  {:from #inst "2025-07-01" :to #inst "2026-07-01"})

(def ^:private as-of-2026 #inst "2026-03-15")

(defn- run-provider
  [conn kind events & {:keys [tax-unit ytd]}]
  (let [provider (inv/au-investment-income-provider {:kind kind})]
    (ptp/period-tax-facts
     provider
     {:db       (d/db conn)
      :entity   :tp
      :period   p2026
      :as-of    as-of-2026
      :tax-unit (or tax-unit {})
      :inputs   (cond-> {:au-investment-income-events events}
                  (some? ytd)
                  (assoc :au-franking-credit-ytd-claimed ytd))})))

(defn- component [facts]
  (first (:components facts)))

(defn- credit-by-code [comp- code]
  (->> (:credits comp-) (filter #(= code (:code %))) first))

(defn- jsc [comp- k]
  (get-in comp- [:jurisdiction-specific-codes k]))

;; ============================================================================
;; §1. Marcus — resident individual, fully franked $700 @ 30 %
;; ============================================================================

(deftest marcus-individual-fully-franked
  (testing "$700 fully franked @ 30 % → FC $300, gross-up $300, refundable"
    (let [conn  (fresh)
          facts (run-provider conn :individual
                              [{:kind :dividend
                                :cash-amount 700M
                                :franking-percent 1M
                                :holding-days 412}])
          comp- (component facts)
          fc    (credit-by-code comp- :au-franking-credit)]
      (is (some? comp-))
      (is (= :investment-income-tax (:kind comp-)))
      (is (= :individual (:regime comp-)))
      ;; franking credit = 700 × 0.30 / 0.70 = 300
      (is (== 300M (-> fc :amount :amount)))
      (is (true? (:refundable? fc)))
      (is (= :refundable (:fate fc)))
      ;; PIT base addition = the gross-up (the cash $700 already on books)
      (is (= [300M] (jsc comp- :pit-base-additions)))
      ;; base on the component = gross-up
      (is (== 300M (-> comp- :base :amount)))
      ;; liability = -300 (integration relief)
      (is (== -300M (-> comp- :liability :amount)))
      ;; small-shareholder exemption applies (total FC $300 ≤ $5,000)
      (is (true? (jsc comp- :small-shareholder-exemption?)))
      (is (= :refundable (jsc comp- :franking-fate)))
      ;; emits-inputs threads the YTD forward
      (is (== 300M (get-in comp- [:jurisdiction-specific-codes
                                  :emits-inputs
                                  :au-franking-credit-ytd-claimed]))))))

;; ============================================================================
;; §2. Murphy SMSF pension phase — fully franked $700 → full $300 refund
;; ============================================================================

(deftest murphy-smsf-pension-refundable
  (testing "Same $700 fully franked → super-fund-pension refundable credit"
    (let [conn  (fresh)
          facts (run-provider conn :super-fund
                              [{:kind :dividend
                                :cash-amount 700M
                                :franking-percent 1M
                                :holding-days 200}]
                              :tax-unit {:super-fund-phase :pension})
          comp- (component facts)
          fc    (credit-by-code comp- :au-franking-credit)]
      (is (some? fc))
      (is (== 300M (-> fc :amount :amount)))
      (is (true? (:refundable? fc)))
      (is (= :refundable (jsc comp- :franking-fate)))
      (is (= :pension (jsc comp- :super-fund-phase))))))

;; ============================================================================
;; §3. SMSF accumulation phase — refundable too (15 % fund tax downstream)
;; ============================================================================

(deftest smsf-accumulation-refundable
  (let [conn  (fresh)
        facts (run-provider conn :super-fund
                            [{:kind :dividend
                              :cash-amount 700M
                              :franking-percent 1M
                              :holding-days 90}]
                            :tax-unit {:super-fund-phase :accumulation})
        comp- (component facts)
        fc    (credit-by-code comp- :au-franking-credit)]
    (is (some? fc))
    (is (true? (:refundable? fc)))
    (is (= :accumulation (jsc comp- :super-fund-phase)))))

;; ============================================================================
;; §4. Non-fixed trust (no FTE) — franking LOST
;; ============================================================================

(deftest non-fixed-trust-no-fte-loses-franking
  (testing "Non-fixed trust without FTE: franking credit LOST, no gross-up"
    (let [conn  (fresh)
          facts (run-provider conn :trust
                              [{:kind :dividend
                                :cash-amount 700M
                                :franking-percent 1M
                                :holding-days 200}]
                              :tax-unit {:trust-kind :discretionary-no-fte})
          comp- (component facts)
          fc    (credit-by-code comp- :au-franking-credit)]
      ;; The credit item is still surfaced for audit, but fate = :lost
      ;; and refundable? = false.
      (is (some? fc))
      (is (= :lost (:fate fc)))
      (is (false? (:refundable? fc)))
      (is (= :lost (jsc comp- :franking-fate)))
      ;; The gross-up is DROPPED — only the cash is assessable (which
      ;; the consumer already booked); no PIT base addition for the
      ;; gross-up.
      (is (nil? (jsc comp- :pit-base-additions)))
      ;; Liability is 0 (credit lost → no integration relief)
      (is (== 0M (-> comp- :liability :amount))))))

;; ============================================================================
;; §5. Fixed trust — passthrough (refundable to the trust layer)
;; ============================================================================

(deftest fixed-trust-passthrough
  (let [conn  (fresh)
        facts (run-provider conn :trust
                            [{:kind :dividend
                              :cash-amount 700M
                              :franking-percent 1M
                              :holding-days 100}]
                            :tax-unit {:trust-kind :fixed})
        comp- (component facts)
        fc    (credit-by-code comp- :au-franking-credit)]
    (is (true? (:refundable? fc)))
    (is (= :refundable (jsc comp- :franking-fate)))
    (is (= :fixed (jsc comp- :trust-kind)))
    (is (= [300M] (jsc comp- :pit-base-additions)))))

;; ============================================================================
;; §6. :discretionary-fte raises :not-yet-implemented
;; ============================================================================

(deftest fte-trust-not-yet-implemented
  (let [conn (fresh)]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #":discretionary-fte not yet implemented"
         (run-provider conn :trust
                       [{:kind :dividend
                         :cash-amount 700M
                         :franking-percent 1M
                         :holding-days 100}]
                       :tax-unit {:trust-kind :discretionary-fte})))))

;; ============================================================================
;; §7. Sarah day-trader — small-shareholder exemption fires
;; ============================================================================

(deftest small-shareholder-exemption-fires
  (testing "Sarah held 18 days but FC $428.57 ≤ $5k → exemption fires → credit usable"
    (let [conn  (fresh)
          facts (run-provider conn :individual
                              [{:kind :dividend
                                :cash-amount 1000M
                                :franking-percent 1M
                                :holding-days 18}])
          comp- (component facts)
          fc    (credit-by-code comp- :au-franking-credit)]
      (is (true? (jsc comp- :small-shareholder-exemption?)))
      (is (some? fc))
      (is (true? (:refundable? fc)))
      ;; FC = 1000 × 0.30 / 0.70 ≈ 428.571...
      (let [fc-amt (-> fc :amount :amount)]
        (is (< (Math/abs (double (- fc-amt 428.5714285714285714M))) 1e-6))))))

;; ============================================================================
;; §8. YTD already at $5k → exemption FAILS → credit denied (45-day fails)
;; ============================================================================

(deftest small-shareholder-exemption-exceeded-credit-denied
  (testing "YTD FC already at $5,000 → new event with 18-day hold → credit DENIED"
    (let [conn  (fresh)
          facts (run-provider conn :individual
                              [{:kind :dividend
                                :cash-amount 1000M
                                :franking-percent 1M
                                :holding-days 18}]
                              :ytd 5000M)
          comp- (component facts)
          fc    (credit-by-code comp- :au-franking-credit)]
      (is (false? (jsc comp- :small-shareholder-exemption?)))
      ;; Credit was disqualified — period franking-credit-total drops
      ;; to zero for this event.
      (is (nil? fc))
      ;; No gross-up → no PIT base addition.
      (is (nil? (jsc comp- :pit-base-additions))))))

;; ============================================================================
;; §9. BRE 25 % distributor
;; ============================================================================

(deftest bre-25-distributor
  (testing "$700 fully franked from BRE @ 25 % → FC = 700 × 0.25/0.75 ≈ 233.33"
    (let [conn  (fresh)
          facts (run-provider conn :individual
                              [{:kind :dividend
                                :cash-amount 700M
                                :franking-percent 1M
                                :elective-regime #{:au-frank-distributor-bre}
                                :holding-days 200}])
          comp- (component facts)
          fc    (credit-by-code comp- :au-franking-credit)
          fc-amt (-> fc :amount :amount)]
      (is (some? fc))
      ;; FC = 700 × 0.25 / 0.75 ≈ 233.333333...
      (is (< (Math/abs (double (- fc-amt 233.3333333333333333M))) 1e-6)))))

;; ============================================================================
;; §10. Foreign-source dividend with FITO — non-refundable
;; ============================================================================

(deftest foreign-dividend-fito-non-refundable
  (testing "Tom: AUD $1,500 US Apple div + $225 US WHT → FITO $225 non-refundable"
    (let [conn  (fresh)
          facts (run-provider conn :individual
                              [{:kind :dividend
                                :cash-amount 1500M
                                :foreign-jurisdiction :us
                                :foreign-tax-withheld 225M
                                :holding-days 365}])
          comp- (component facts)
          fito  (credit-by-code comp- :au-fito)]
      (is (some? fito))
      (is (== 225M (-> fito :amount :amount)))
      (is (false? (:refundable? fito)))
      (is (= :non-refundable (:fate fito)))
      ;; The full AUD $1,500 foreign-source gross folds to PIT base (no
      ;; Australian gross-up; it's a foreign source).
      (is (= [1500M] (jsc comp- :pit-base-additions)))
      ;; No franking credit (foreign source)
      (is (nil? (credit-by-code comp- :au-franking-credit)))
      ;; Liability = -225 (the FITO integration)
      (is (== -225M (-> comp- :liability :amount))))))

;; ============================================================================
;; §11. Resident interest with TFN supplied — no credit, folds to PIT base
;; ============================================================================

(deftest interest-tfn-supplied
  (let [conn  (fresh)
        facts (run-provider conn :individual
                            [{:kind :interest
                              :cash-amount 2000M
                              :tfn-prepaid 0M}])
        comp- (component facts)]
    (is (some? comp-))
    ;; Interest folds to PIT base.
    (is (= [2000M] (jsc comp- :pit-base-additions)))
    ;; No credit (TFN prepaid = 0)
    (is (empty? (:credits comp-)))
    (is (== 0M (-> comp- :liability :amount)))))

;; ============================================================================
;; §12. Resident interest with no TFN — 47 % refundable prepayment
;; ============================================================================

(deftest interest-no-tfn-refundable-prepayment
  (testing "$2,000 gross interest with 47 % TFN withheld → refundable credit $940"
    (let [conn  (fresh)
          facts (run-provider conn :individual
                              [{:kind :interest
                                :cash-amount 2000M
                                :tfn-prepaid 940M}])
          comp- (component facts)
          tfn   (credit-by-code comp- :au-tfn-prepaid)]
      (is (some? tfn))
      (is (== 940M (-> tfn :amount :amount)))
      (is (true? (:refundable? tfn)))
      ;; PIT base gets the GROSS interest ($2,000).
      (is (= [2000M] (jsc comp- :pit-base-additions)))
      ;; Prepaid surfaces as :prepaid on the component too.
      (is (== 940M (-> comp- :prepaid :amount)))
      ;; Liability = -940 (refundable integration)
      (is (== -940M (-> comp- :liability :amount))))))

;; ============================================================================
;; §13. Resident company — non-refundable + franking-account-credit-pending
;; ============================================================================

(deftest company-non-refundable-with-fac-pending
  (testing "InvestCo Pty Ltd receives $1,000 fully franked @ 30 % → CIT path"
    (let [conn  (fresh)
          facts (run-provider conn :company
                              [{:kind :dividend
                                :cash-amount 1000M
                                :franking-percent 1M
                                :holding-days 365}])
          comp- (component facts)
          fc    (credit-by-code comp- :au-franking-credit)]
      (is (some? fc))
      ;; Fate :non-refundable for company
      (is (= :non-refundable (:fate fc)))
      (is (false? (:refundable? fc)))
      ;; Gross-up flows to CIT base (not PIT)
      (is (= [(-> fc :amount :amount)] (jsc comp- :cit-base-additions)))
      (is (nil? (jsc comp- :pit-base-additions)))
      ;; Franking-account-credit-pending signal emitted
      (is (some? (jsc comp- :au-franking-account-credit-pending)))
      ;; Company liability — franking credit is NOT used at the
      ;; investment-income layer (it flows to the franking account);
      ;; integration is 0 at this provider's component.
      (is (== 0M (-> comp- :liability :amount))))))

;; ============================================================================
;; §14. Foreign resident — franking lost; foreign source income dropped
;; ============================================================================

(deftest foreign-resident-franking-lost
  (let [conn  (fresh)
        facts (run-provider conn :individual
                            [{:kind :dividend
                              :cash-amount 700M
                              :franking-percent 1M
                              :holding-days 365}]
                            :tax-unit {:foreign-resident? true})
        comp- (component facts)
        fc    (credit-by-code comp- :au-franking-credit)]
    (is (true? (jsc comp- :foreign-resident?)))
    (is (= :lost (:fate fc)))
    (is (false? (:refundable? fc)))
    (is (nil? (jsc comp- :pit-base-additions)))
    (is (== 0M (-> comp- :liability :amount)))))

;; ============================================================================
;; §15. Kind validation
;; ============================================================================

(deftest invalid-kind-rejected
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #":kind must be"
       (inv/au-investment-income-provider {:kind :bogus}))))

(deftest invalid-super-fund-phase-rejected
  (let [conn (fresh)]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #":super-fund-phase must be"
         (run-provider conn :super-fund
                       [{:kind :dividend
                         :cash-amount 700M
                         :holding-days 200}]
                       :tax-unit {:super-fund-phase :bogus})))))

(deftest invalid-trust-kind-rejected
  (let [conn (fresh)]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #":trust-kind must be"
         (run-provider conn :trust
                       [{:kind :dividend
                         :cash-amount 700M
                         :holding-days 200}]
                       :tax-unit {:trust-kind :bogus})))))

;; ============================================================================
;; §16. Empty events — empty :components
;; ============================================================================

(deftest empty-events-empty-components
  (let [conn  (fresh)
        facts (run-provider conn :individual [])]
    (is (empty? (:components facts)))))

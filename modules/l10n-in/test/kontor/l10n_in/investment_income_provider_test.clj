(ns kontor.l10n-in.investment-income-provider-test
  "Tests for the IN investment-income provider (research note 156).

   Worked examples — Mr. Mehta (resident individual; mixed dividend +
   interest + MF IDCW; new regime) and Mr. Patel (NRI; §115A definitive
   with DTAA reduction) per note 156 §2."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.l10n-in.investment-income-provider :as inv]
            [kontor.l10n-in.investment-income-statute :as inv-statute]
            [kontor.period-tax-provider :as ptp]))

;; ============================================================================
;; Fixture
;; ============================================================================

(defn- fresh
  "Fresh DB with the IN investment-income statute installed + an INR
   commodity."
  []
  (let [conn (core/create-test-db)]
    (inv-statute/install! conn)
    (d/transact conn [{:kontor.commodity/symbol    "INR"
                       :kontor.commodity/name      "Indian Rupee"
                       :kontor.commodity/precision 2}])
    conn))

;; Indian fiscal year: 1 Apr → 31 Mar.
(def ^:private fy-2025-26 {:from #inst "2025-04-01" :to #inst "2026-04-01"})
(def ^:private fy-2024-25 {:from #inst "2024-04-01" :to #inst "2025-04-01"})
(def ^:private fy-2026-27 {:from #inst "2026-04-01" :to #inst "2027-04-01"})

(defn- run-provider
  "Build and run the provider; return the resulting `TaxReturnFacts`."
  [conn kind opts ctx]
  (let [provider (inv/in-investment-income-provider
                  (merge {:kind kind} opts))]
    (ptp/period-tax-facts
     provider
     (merge {:db     (d/db conn)
             :entity nil
             :period fy-2025-26
             :as-of  (:to fy-2025-26)}
            ctx))))

(defn- component-by-lane
  "Pick the component with matching `:jurisdiction-specific-codes :lane`."
  [facts lane]
  (->> (:components facts)
       (filter #(= lane (get-in % [:jurisdiction-specific-codes :lane])))
       first))

;; ============================================================================
;; §1. Construction sanity
;; ============================================================================

(deftest provider-construction
  (testing "individual + corporation construct cleanly"
    (let [p1 (inv/in-investment-income-provider {:kind :individual})
          p2 (inv/in-investment-income-provider {:kind :corporation})]
      (is (= :in-inv-income-individual (:id p1)))
      (is (= :in-inv-income-corporation (:id p2)))
      (is (= :INR (:commodity p1)))
      (is (= :INR (:commodity p2)))
      (is (satisfies? ptp/PeriodTaxProvider p1))
      (is (satisfies? ptp/PeriodTaxProvider p2))))

  (testing "unknown :kind throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":kind must be"
                          (inv/in-investment-income-provider {:kind :nri})))))

;; ============================================================================
;; §2. Resident dividend — folds via :pit-base-additions
;; ============================================================================

(deftest resident-dividend-folds-to-pit
  (testing "post-FA-2020: dividend at slab via :pit-base-additions; TDS §194 prepayment"
    (let [conn  (fresh)
          facts (run-provider
                 conn :individual {}
                 {:inputs {:in-investment-income
                           {:dividends {:RIL     60000M    ; > ₹10k threshold → TDS
                                        :INFOSYS 8000M}}   ; < threshold
                           :in-tds-withheld {:by-section {:194 6000M}}
                           :in-tax-regime :new-regime-115bac}})
          div   (component-by-lane facts :in-dividend-resident-slab)]
      (is (some? div))
      (is (= :investment-income-tax (:kind div)))
      (is (== 68000M (-> div :base :amount)))
      (is (== 0M    (-> div :liability :amount)) "no standalone liability — folds to PIT")
      (is (== 6000M (-> div :prepaid :amount))    "TDS §194 prepaid")
      (is (= [68000M] (get-in div [:jurisdiction-specific-codes :pit-base-additions])))
      (is (= 1 (get-in div [:jurisdiction-specific-codes :in/payers-above-tds-threshold]))
          "RIL above ₹10k threshold; INFOSYS below"))))

;; ============================================================================
;; §3. §194 threshold bitemporal swap — FA 2025 raised ₹5k → ₹10k
;; ============================================================================

(deftest §194-threshold-fa2025-swap
  (testing "FY 2024-25 (pre-FA-2025): threshold is ₹5 000"
    (let [conn  (fresh)
          facts (run-provider
                 conn :individual {}
                 {:period fy-2024-25
                  ;; FA-2025's effective-from is #inst "2025-04-01"; the
                  ;; pre-FA-2025 ₹5k parameter is in effect for the FY
                  ;; 2024-25 reporting moment 2025-03-31.
                  :as-of  #inst "2025-03-31"
                  :inputs {:in-investment-income
                           {:dividends {:A 6000M    ; > ₹5k → triggers TDS pre-FA-2025
                                        :B 4000M}}  ; < ₹5k
                           :in-tax-regime :new-regime-115bac}})
          div   (component-by-lane facts :in-dividend-resident-slab)]
      (is (= 1 (get-in div [:jurisdiction-specific-codes :in/payers-above-tds-threshold]))
          "only A above the pre-FA-2025 ₹5k threshold")))

  (testing "FY 2025-26 (post-FA-2025): threshold raised to ₹10 000"
    (let [conn  (fresh)
          facts (run-provider
                 conn :individual {}
                 {:period fy-2025-26
                  :as-of  (:to fy-2025-26)
                  :inputs {:in-investment-income
                           {:dividends {:A 6000M    ; < ₹10k now → no TDS
                                        :B 4000M
                                        :C 15000M}} ; > ₹10k → TDS
                           :in-tax-regime :new-regime-115bac}})
          div   (component-by-lane facts :in-dividend-resident-slab)]
      (is (= 1 (get-in div [:jurisdiction-specific-codes :in/payers-above-tds-threshold]))
          "only C exceeds the post-FA-2025 ₹10k threshold"))))

;; ============================================================================
;; §4. TDS prepayment reduces final liability (§194)
;; ============================================================================

(deftest tds-prepayment-recorded
  (testing "TDS amounts feed :prepaid on the resident component"
    (let [conn  (fresh)
          facts (run-provider
                 conn :individual {}
                 {:inputs {:in-investment-income
                           {:dividends {:RIL 100000M}}
                           :in-tds-withheld {:by-section {:194 10000M}}
                           :in-tax-regime :new-regime-115bac}})
          div   (component-by-lane facts :in-dividend-resident-slab)]
      (is (== 10000M (-> div :prepaid :amount)))
      (is (== 100000M (-> div :base :amount)))
      (is (= [100000M] (get-in div [:jurisdiction-specific-codes :pit-base-additions]))
          "the gross dividend folds to PIT; TDS is the prepayment side"))))

;; ============================================================================
;; §5. §80TTA — savings interest deduction (OLD regime only)
;; ============================================================================

(deftest §80tta-deduction-old-regime-only
  (testing "OLD regime + non-senior: §80TTA caps savings interest deduction at ₹10 000"
    (let [conn  (fresh)
          facts (run-provider
                 conn :individual {}
                 {:tax-unit {:senior? false}
                  :inputs {:in-investment-income
                           {:interest-bank   {:HDFC 30000M}    ; FD interest — slab
                            :interest-savings 7000M}            ; under cap
                           :in-tax-regime :old-regime}})
          int-c (component-by-lane facts :in-interest-resident-slab)]
      ;; gross = 30k + 7k = 37k; §80TTA = min(7k, 10k) = 7k
      ;; net pit-base-additions = 37k - 7k = 30k
      (is (some? int-c))
      (is (== 37000M (-> int-c :base :amount)))
      (is (= [30000M] (get-in int-c [:jurisdiction-specific-codes :pit-base-additions]))
          "§80TTA fully absorbs the ₹7k savings interest")
      (is (== 7000M (get-in int-c [:jurisdiction-specific-codes
                                   :in/§80tta-§80ttb-deduction])))))

  (testing "OLD regime + savings interest > ₹10k: §80TTA capped at ₹10k"
    (let [conn  (fresh)
          facts (run-provider
                 conn :individual {}
                 {:tax-unit {:senior? false}
                  :inputs {:in-investment-income
                           {:interest-savings 25000M}
                           :in-tax-regime :old-regime}})
          int-c (component-by-lane facts :in-interest-resident-slab)]
      (is (== 10000M (get-in int-c [:jurisdiction-specific-codes
                                    :in/§80tta-§80ttb-deduction]))
          "capped at ₹10k")
      (is (= [15000M] (get-in int-c [:jurisdiction-specific-codes :pit-base-additions]))
          "₹25k − ₹10k cap = ₹15k to PIT")))

  (testing "NEW regime: §80TTA NOT available (provision condition fails)"
    (let [conn  (fresh)
          facts (run-provider
                 conn :individual {}
                 {:tax-unit {:senior? false}
                  :inputs {:in-investment-income
                           {:interest-savings 7000M}
                           :in-tax-regime :new-regime-115bac}})
          int-c (component-by-lane facts :in-interest-resident-slab)]
      (is (== 0M (get-in int-c [:jurisdiction-specific-codes
                                :in/§80tta-§80ttb-deduction]))
          "§115BAC new regime disallows §80TTA")
      (is (= [7000M] (get-in int-c [:jurisdiction-specific-codes :pit-base-additions]))
          "full interest folds to PIT under new regime"))))

;; ============================================================================
;; §6. §80TTB — senior all-interest deduction (OLD regime only)
;; ============================================================================

(deftest §80ttb-senior-deduction
  (testing "OLD regime + senior: §80TTB caps ALL interest at ₹50 000"
    (let [conn  (fresh)
          facts (run-provider
                 conn :individual {}
                 {:tax-unit {:senior? true}
                  :inputs {:in-investment-income
                           {:interest-bank    {:HDFC 30000M
                                               :SBI  25000M}
                            :interest-savings 8000M}            ; all interest counts
                           :in-tax-regime :old-regime}})
          int-c (component-by-lane facts :in-interest-resident-slab)]
      ;; gross = 30k + 25k + 8k = 63k; §80TTB = min(63k, 50k) = 50k
      (is (== 63000M (-> int-c :base :amount)))
      (is (== 50000M (get-in int-c [:jurisdiction-specific-codes
                                    :in/§80tta-§80ttb-deduction]))
          "capped at ₹50k senior limit")
      (is (= [13000M] (get-in int-c [:jurisdiction-specific-codes :pit-base-additions]))
          "₹63k − ₹50k = ₹13k folds to PIT")))

  (testing "senior cannot claim §80TTA (super-seded by §80TTB; condition gates on senior?)"
    (let [conn  (fresh)
          ;; A senior with ₹3k savings interest only: §80TTB caps at ₹3k
          ;; (≤ ₹50k); the §80TTA provision condition fails (senior? true).
          facts (run-provider
                 conn :individual {}
                 {:tax-unit {:senior? true}
                  :inputs {:in-investment-income
                           {:interest-savings 3000M}
                           :in-tax-regime :old-regime}})
          int-c (component-by-lane facts :in-interest-resident-slab)]
      (is (== 3000M (get-in int-c [:jurisdiction-specific-codes
                                   :in/§80tta-§80ttb-deduction]))
          "§80TTB applies — caps below ₹50k limit")
      (is (= [0M] (get-in int-c [:jurisdiction-specific-codes :pit-base-additions]))
          "fully absorbed; net addition zero"))))

;; ============================================================================
;; §7. MF IDCW (§194K) — folds via :pit-base-additions
;; ============================================================================

(deftest mf-idcw-folds-to-pit
  (testing "MF IDCW post-FA-2020 at slab; §194K TDS prepayment"
    (let [conn  (fresh)
          facts (run-provider
                 conn :individual {}
                 {:inputs {:in-investment-income
                           {:mf-idcw {:SBI-MF 25000M    ; > ₹10k → TDS
                                      :HDFC-MF 5000M}}  ; under threshold
                           :in-tds-withheld {:by-section {:194K 2500M}}
                           :in-tax-regime :new-regime-115bac}})
          mf    (component-by-lane facts :in-mf-idcw-resident-slab)]
      (is (== 30000M (-> mf :base :amount)))
      (is (== 0M     (-> mf :liability :amount)))
      (is (== 2500M  (-> mf :prepaid :amount)))
      (is (= [30000M] (get-in mf [:jurisdiction-specific-codes :pit-base-additions])))
      (is (= 1 (get-in mf [:jurisdiction-specific-codes :in/amcs-above-tds-threshold])))))

  (testing "§194K threshold also raised ₹5k → ₹10k by FA 2025"
    (let [conn  (fresh)
          ;; pre-FA-2025: ₹6k > ₹5k threshold → 1 AMC above
          facts (run-provider
                 conn :individual {}
                 {:period fy-2024-25
                  ;; FA-2025 effective 1 April 2025; the pre-FA-2025
                  ;; ₹5k MF IDCW parameter is in effect for FY 2024-25.
                  :as-of  #inst "2025-03-31"
                  :inputs {:in-investment-income
                           {:mf-idcw {:A 6000M}}
                           :in-tax-regime :new-regime-115bac}})
          mf    (component-by-lane facts :in-mf-idcw-resident-slab)]
      (is (= 1 (get-in mf [:jurisdiction-specific-codes :in/amcs-above-tds-threshold]))
          "pre-FA-2025 ₹5k threshold"))))

;; ============================================================================
;; §8. NRI §115A — 20 % flat + surcharge cap + cess (DEFINITIVE)
;; ============================================================================

(deftest nri-§115a-definitive
  (testing "NRI dividend at statutory 20 %: flat schedule, surcharge cap, cess"
    (let [conn  (fresh)
          facts (run-provider
                 conn :individual {}
                 {:tax-unit {:nri? true}
                  :inputs {:in-investment-income
                           {:nri-dividends 1000000M}
                           :in-tds-withheld {:by-section {:195 200000M}}}})
          nri   (component-by-lane facts :in-nri-§115a-dividend)]
      ;; gross = 1 000 000 × 0.20 = 200 000
      ;; surcharge = 200 000 × 0.15 = 30 000
      ;; cess = (200 000 + 30 000) × 0.04 = 9 200
      ;; total = 200 000 + 30 000 + 9 200 = 239 200
      (is (some? nri))
      (is (= :investment-income-tax (:kind nri)))
      (is (= :nri-§115a (:regime nri)))
      (is (== 200000M (-> nri :gross-liability :amount)))
      (is (== 239200M (-> nri :liability :amount))
          "200k tax + 30k surcharge (15% cap) + 9.2k cess (4% on tax+surcharge)")
      (is (== 200000M (-> nri :prepaid :amount)) "§195 TDS prepayment")
      (is (false? (get-in nri [:jurisdiction-specific-codes :in/dtaa-applied?])))))

  (testing "NRI dividend with DTAA-reduced rate (15% per US-India treaty)"
    (let [conn  (fresh)
          facts (run-provider
                 conn :individual {}
                 {:tax-unit {:nri? true}
                  :inputs {:in-investment-income
                           {:nri-dividends 1500000M
                            :nri-dtaa-rate 0.15M}}})
          nri   (component-by-lane facts :in-nri-§115a-dividend)]
      ;; gross = 1.5M × 0.15 = 225 000
      ;; surcharge = 225 000 × 0.15 = 33 750
      ;; cess = (225 000 + 33 750) × 0.04 = 10 350
      ;; total = 225 000 + 33 750 + 10 350 = 269 100
      (is (== 225000M (-> nri :gross-liability :amount)))
      (is (== 269100M (-> nri :liability :amount))
          "matches note 156 §2 Example B Mr Patel exactly")
      (is (true? (get-in nri [:jurisdiction-specific-codes :in/dtaa-applied?])))
      (is (== 0.15M (get-in nri [:jurisdiction-specific-codes :in/applied-rate]))))))

;; ============================================================================
;; §9. FA-2024 dividend surcharge cap surfaced on resident component
;; ============================================================================

(deftest fa2024-dividend-surcharge-cap
  (testing "resident dividend component surfaces 15% surcharge cap hint to PIT"
    (let [conn  (fresh)
          facts (run-provider
                 conn :individual {}
                 {:inputs {:in-investment-income
                           {:dividends {:RIL 5000000M}}    ; ₹50 L dividend
                           :in-tax-regime :new-regime-115bac}})
          div   (component-by-lane facts :in-dividend-resident-slab)]
      (is (== 0.15M (get-in div [:jurisdiction-specific-codes
                                 :in/dividend-surcharge-cap]))
          "consumer threads this to IN PIT to override the 25%/37% bands"))))

;; ============================================================================
;; §10. Corporate dividend — §80M chain-relief
;; ============================================================================

(deftest corporate-§80m-chain-relief
  (testing "corp recipient redistributes within window: §80M deduction reduces CIT base"
    (let [conn  (fresh)
          provider (inv/in-investment-income-provider {:kind :corporation})
          facts (ptp/period-tax-facts
                 provider
                 {:db (d/db conn) :entity nil :period fy-2025-26
                  :as-of (:to fy-2025-26)
                  :inputs {:in-investment-income
                           {:dividends {:SUBSIDIARY 1000000M}
                            :§80m-redistribution 800000M}
                           :in-tds-withheld {:by-section {:194 100000M}}}})
          corp  (component-by-lane facts :in-corp-dividend)]
      (is (some? corp))
      (is (= :investment-income-tax (:kind corp)))
      (is (== 800000M (get-in corp [:jurisdiction-specific-codes :in/§80m-deduction]))
          "§80M-deduction = min(received, redistributed) = ₹8 L")
      (is (== 100000M (-> corp :prepaid :amount)) "TDS prepaid")
      ;; Per cross-jurisdiction convention (CA §112 / DE §8b / CN §26(2)),
      ;; gross dividend goes to :cit-base-additions; §80M deduction
      ;; surfaces separately as :cit-base-deductions (auditable).
      (is (= [1000000M] (get-in corp [:jurisdiction-specific-codes :cit-base-additions]))
          "gross dividend ₹10 L into :cit-base-additions")
      (is (= [800000M] (get-in corp [:jurisdiction-specific-codes :cit-base-deductions]))
          "§80M chain relief ₹8 L into :cit-base-deductions (downstream CIT nets)")))

  (testing "corp with foreign dividend: gross to CIT + foreign-tax-credit hint"
    (let [conn  (fresh)
          provider (inv/in-investment-income-provider {:kind :corporation})
          facts (ptp/period-tax-facts
                 provider
                 {:db (d/db conn) :entity nil :period fy-2025-26
                  :as-of (:to fy-2025-26)
                  :inputs {:in-investment-income
                           {:dividends {:DOM 500000M}
                            :foreign-dividends 300000M}}})
          corp  (component-by-lane facts :in-corp-dividend)]
      (is (some? corp))
      (is (== 800000M (-> corp :base :amount)) "domestic + foreign gross")
      (is (= [500000M 300000M]
             (get-in corp [:jurisdiction-specific-codes :cit-base-additions]))
          "domestic-net + foreign separately for the consumer to split")
      (is (== 300000M (get-in corp [:jurisdiction-specific-codes :in/foreign-tax-credit]))))))

;; ============================================================================
;; §11. ITA 2025 renumbering — bitemporal label swap
;; ============================================================================

(deftest ita-2025-renumbering-label-swap
  (testing "FY 2025-26 (pre-1-Apr-2026): line items use 1961 numbering (§194)"
    (let [conn  (fresh)
          facts (run-provider
                 conn :individual {}
                 {:as-of  #inst "2026-03-31"
                  :inputs {:in-investment-income
                           {:dividends {:A 50000M}}
                           :in-tax-regime :new-regime-115bac}})
          div   (component-by-lane facts :in-dividend-resident-slab)
          tds-line (some #(when (= :tds-§194 (:line %)) %) (:line-items div))]
      (is (some? tds-line))
      (is (re-find #"§194\b" (:label tds-line)) "1961-Act label")
      (is (not (re-find #"§393" (:label tds-line))) "not the IT-Act-2025 label")))

  (testing "FY 2026-27 (post-1-Apr-2026): line items use IT-Act-2025 numbering"
    (let [conn  (fresh)
          facts (run-provider
                 conn :individual {}
                 {:period fy-2026-27
                  :as-of  (:to fy-2026-27)
                  :inputs {:in-investment-income
                           {:dividends {:A 50000M}}
                           :in-tax-regime :new-regime-115bac}})
          div   (component-by-lane facts :in-dividend-resident-slab)
          tds-line (some #(when (= :tds-§194 (:line %)) %) (:line-items div))]
      (is (some? tds-line))
      (is (re-find #"§393\(1\)" (:label tds-line)) "IT-Act-2025 renumbering")
      (is (not (re-find #"§194\b" (:label tds-line)))))))

;; ============================================================================
;; §12. End-to-end — Mr. Mehta (note 156 §2 Example A)
;; ============================================================================

(deftest mr-mehta-end-to-end
  (testing "Mr. Mehta — resident; mixed dividend + FD interest + MF IDCW + savings; new regime"
    (let [conn  (fresh)
          facts (run-provider
                 conn :individual {}
                 {:tax-unit {:senior? false}
                  :inputs {:in-investment-income
                           {:dividends        {:RIL     60000M       ; TDS-triggering
                                               :INFOSYS 8000M}        ; sub-threshold
                            :interest-bank    {:HDFC 120000M}         ; TDS-triggering
                            :interest-savings 7000M
                            :mf-idcw          {:SBI-MF 25000M}}       ; TDS-triggering
                           :in-tds-withheld {:by-section {:194  6000M
                                                          :194A 12000M
                                                          :194K 2500M}}
                           :in-tax-regime :new-regime-115bac}})
          div   (component-by-lane facts :in-dividend-resident-slab)
          int-c (component-by-lane facts :in-interest-resident-slab)
          mf    (component-by-lane facts :in-mf-idcw-resident-slab)]
      ;; per note 156 §2: dividends 68k, interest 127k (120 + 7),
      ;; mf-idcw 25k; new regime → §80TTA NOT available → 7k savings
      ;; flows full. TDS prepayments 6 + 12 + 2.5 = 20.5k.
      (is (== 68000M  (-> div :base :amount)))
      (is (== 127000M (-> int-c :base :amount)))
      (is (== 25000M  (-> mf :base :amount)))
      (is (== 6000M   (-> div :prepaid :amount)))
      (is (== 12000M  (-> int-c :prepaid :amount)))
      (is (== 2500M   (-> mf :prepaid :amount)))
      ;; §80TTA suppressed under new regime
      (is (== 0M (get-in int-c [:jurisdiction-specific-codes
                                :in/§80tta-§80ttb-deduction])))
      ;; PIT base additions: dividend 68k, interest 127k (no deduction),
      ;; MF IDCW 25k → total fold = 220k.
      (is (= [68000M]  (get-in div   [:jurisdiction-specific-codes :pit-base-additions])))
      (is (= [127000M] (get-in int-c [:jurisdiction-specific-codes :pit-base-additions])))
      (is (= [25000M]  (get-in mf    [:jurisdiction-specific-codes :pit-base-additions])))
      ;; Total TDS = 20.5k — the consumer aggregates this into PIT's
      ;; prepaid bucket (or the investment-income provider's prepaid
      ;; sum is computed component-wise; sum them here for the audit
      ;; assertion).
      (let [total-prepaid (reduce + 0M
                                  (map #(some-> % :prepaid :amount)
                                       (:components facts)))]
        (is (== 20500M total-prepaid)
            "matches note 156 §2 Example A TDS prepayment ₹20.5k")))))

;; ============================================================================
;; §13. Component kind always :investment-income-tax
;; ============================================================================

(deftest components-use-investment-income-tax-kind
  (let [conn  (fresh)
        facts (run-provider
               conn :individual {}
               {:tax-unit {:senior? false}
                :inputs {:in-investment-income
                         {:dividends        {:A 20000M}
                          :interest-bank    {:HDFC 50000M}
                          :interest-savings 5000M
                          :mf-idcw          {:M 15000M}}
                         :in-tax-regime :old-regime}})]
    (is (= 3 (count (:components facts))))
    (is (every? #(= :investment-income-tax (:kind %)) (:components facts))
        "all components carry the new period-tax kind enum")))

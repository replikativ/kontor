(ns kontor.l10n-cn.investment-income-provider-test
  "Tests for the CN IIT + EIT investment-income providers (ADR-099 +
   ADR-101).

   Covers:
   - IIT category 7 — 20 % flat for dividends/interest.
   - Listed A-share holding gradation (≤ 1m full / 1m–1y half / > 1y exempt)
     via ADR-101 Addendum 1 :op :schedule-override.
   - Stock Connect H-share exemption via ADR-101 Addendum 2
     period-from-before (sunset 2027-12-31).
   - Bank deposit + government bond interest exemptions (no component).
   - Non-resident WHT (10 % corporate / 20 % individual, treaty-reducible).
   - EIT §26(2) inter-TRR exemption (TRR + > 12m + no partnership chain).
   - Partnership-veil footgun (Caishui [2008] 159 §4): partnership in
     :holding-chain DENIES §26(2) exemption.
   - Worked examples from."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.l10n-cn.investment-income-provider :as inv]
            [kontor.l10n-cn.investment-income-statute :as inv-statute]
            [kontor.tax.period-tax-provider :as ptp]
            [kontor.tax.statute]))

;; ============================================================================
;; Fixture
;; ============================================================================

(defn- fresh
  "Fresh DB with the CN investment-income statute and a CNY commodity."
  []
  (let [conn (core/create-test-db)]
    (inv-statute/install! conn)
    (d/transact conn [{:kontor.commodity/symbol "CNY" :kontor.commodity/name "Chinese Yuan"
                       :kontor.commodity/precision 2}
                      {:kontor.entity/code "HOLDCO" :kontor.entity/name "HoldCo"
                       :kontor.entity/kind :company :kontor.entity/country "CN"
                       :kontor.entity/functional-commodity [:kontor.commodity/symbol "CNY"]}])
    conn))

(def ^:private cny [:kontor.commodity/symbol "CNY"])

(defn- holdco-eid [conn]
  (d/q '[:find ?e . :where [?e :kontor.entity/code "HOLDCO"]] (d/db conn)))

(def ^:private p2026 {:from #inst "2026-01-01" :to #inst "2027-01-01"})
(def ^:private p2028 {:from #inst "2028-01-01" :to #inst "2029-01-01"})

(defn- run-iit
  "Build the IIT investment-income provider, call `period-tax-facts`."
  [conn events & [extra-ctx]]
  (let [provider (inv/cn-iit-investment-income-provider {})]
    (ptp/period-tax-facts
     provider
     (merge {:db     (d/db conn)
             :entity (holdco-eid conn)
             :period p2026
             :inputs {:investment-income-events events}}
            extra-ctx))))

(defn- run-eit
  [conn events & [extra-ctx]]
  (let [provider (inv/cn-eit-investment-income-provider {})]
    (ptp/period-tax-facts
     provider
     (merge {:db     (d/db conn)
             :entity (holdco-eid conn)
             :period p2026
             :inputs {:investment-income-events events}}
            extra-ctx))))

(defn- component-by-lane [facts lane]
  (->> (:components facts)
       (filter #(= lane (get-in % [:jurisdiction-specific-codes :lane])))
       first))

;; ============================================================================
;; §1. Plumbing — empty events, statute install, enum closure
;; ============================================================================

(deftest empty-events-returns-no-components
  (testing "no events → empty :components vec for both providers"
    (let [conn (fresh)
          iit-facts (run-iit conn []
                             {:tax-unit {:tax-residency :resident-individual}})
          eit-facts (run-eit conn []
                             {:tax-unit {:tax-residency :resident-corporation}})]
      (is (empty? (:components iit-facts)))
      (is (empty? (:components eit-facts))))))

(deftest statute-parameters-installed
  (testing "IIT/EIT investment-income parameters loaded; current-effective values"
    (let [conn (fresh)
          db   (d/db conn)
          ;; Use kontor.tax.statute/parameter-value-at — the same path the
          ;; provider takes — to validate effective-window resolution.
          at   #inst "2026-01-01"
          pv   (fn [c] (kontor.tax.statute/parameter-value-at db c at))]
      (is (= 0.20M (pv "CN.IIT.investment-income.flat-rate")))
      (is (= 0M    (pv "CN.IIT.investment-income.stock-connect-rate")))
      (is (= 0M    (pv "CN.IIT.investment-income.bank-deposit-rate")))
      (is (= 0.10M (pv "CN.EIT.outbound-wht-rate")))
      (is (= 12M   (pv "CN.EIT.investment-income.inter-TRR-hold-months")))
      ;; Bracket factors for Caishui [2015] 101 gradation
      (is (= 1.00M (pv "CN.IIT.investment-income.listed-A.le-1m-factor")))
      (is (= 0.50M (pv "CN.IIT.investment-income.listed-A.1m-1y-factor")))
      (is (= 0M    (pv "CN.IIT.investment-income.listed-A.gt-1y-factor"))))))

;; ============================================================================
;; §2. IIT — listed A-share gradation (Caishui [2015] 101) —
;; ============================================================================

(deftest iit-listed-a-share-le-1m-full-rate
  (testing "≤ 1 month holding → 20 % full (lot L3 from)"
    (let [conn (fresh)
          facts (run-iit conn
                         [{:event-id     "catl-L3"
                           :income-class :listed-a-share-dividend
                           :amount       1000M
                           :holding-days 12}]
                         {:tax-unit {:tax-residency :resident-individual}})
          iit   (component-by-lane facts :cn-iit-investment-income)]
      (is (some? iit))
      (is (== 1000M (-> iit :base :amount)))
      (is (== 200M (-> iit :gross-liability :amount))
          "≤ 1m: 1000 × 20 % = 200"))))

(deftest iit-listed-a-share-1m-1y-half-rate
  (testing "1m–1y holding → 10 % effective (lot L2 from)"
    (let [conn (fresh)
          facts (run-iit conn
                         [{:event-id     "catl-L2"
                           :income-class :listed-a-share-dividend
                           :amount       1400M
                           :holding-days 95}]
                         {:tax-unit {:tax-residency :resident-individual}})
          iit   (component-by-lane facts :cn-iit-investment-income)]
      (is (some? iit))
      (is (== 140M (-> iit :gross-liability :amount))
          "1m–1y: 1400 × 10 % = 140"))))

(deftest iit-listed-a-share-gt-1y-exempt
  (testing "> 1 year holding → fully exempt (lot L1 from)"
    (let [conn (fresh)
          facts (run-iit conn
                         [{:event-id     "catl-L1"
                           :income-class :listed-a-share-dividend
                           :amount       1600M
                           :holding-days 727}]
                         {:tax-unit {:tax-residency :resident-individual}})
          iit   (component-by-lane facts :cn-iit-investment-income)]
      (is (some? iit))
      (is (== 0M (-> iit :gross-liability :amount))
          "> 1y holding → 0 IIT (full exemption)"))))

(deftest iit-listed-a-share-all-three-bands-mixed
  (testing "Ms Liu's three lots aggregate to CNY 340"
    (let [conn (fresh)
          facts (run-iit conn
                         [;; L1: 727 days, gt-1y → 0 tax
                          {:event-id     "catl-L1"
                           :income-class :listed-a-share-dividend
                           :amount       1600M
                           :holding-days 727}
                          ;; L2: 95 days, 1m-1y → 140
                          {:event-id     "catl-L2"
                           :income-class :listed-a-share-dividend
                           :amount       1400M
                           :holding-days 95}
                          ;; L3: 12 days, le-1m → 200
                          {:event-id     "catl-L3"
                           :income-class :listed-a-share-dividend
                           :amount       1000M
                           :holding-days 12}]
                         {:tax-unit {:tax-residency :resident-individual}})
          iit   (component-by-lane facts :cn-iit-investment-income)]
      (is (some? iit))
      (is (== 340M (-> iit :gross-liability :amount))
          "Note 158 §2 ex A worked total = 340"))))

;; ============================================================================
;; §3. Stock Connect H-share — sunset 2027-12-31 (ADR-101 Addendum 2)
;; ============================================================================

(deftest stock-connect-h-share-pre-2028-exempt
  (testing "Stock Connect H-share dividend pre-2028-01-01 → exempt"
    (let [conn (fresh)
          facts (run-iit conn
                         [{:event-id     "tencent-1"
                           :income-class :stock-connect-h-share-dividend
                           :amount       50000M
                           :holding-days 200}]
                         {:tax-unit {:tax-residency :resident-individual}})
          iit   (component-by-lane facts :cn-iit-investment-income)]
      (is (some? iit))
      (is (== 0M (-> iit :gross-liability :amount))
          "Caishui [2014] 81 — exempt through 2027-12-31"))))

(deftest stock-connect-h-share-post-2028-default-20pct
  (testing "Stock Connect H-share post-2028 (sunset passed) → default 20 %"
    (let [conn (fresh)
          provider (inv/cn-iit-investment-income-provider {})
          facts (ptp/period-tax-facts
                 provider
                 {:db     (d/db conn)
                  :entity (holdco-eid conn)
                  :period p2028
                  :inputs {:investment-income-events
                           [{:event-id     "tencent-2"
                             :income-class :stock-connect-h-share-dividend
                             :amount       50000M
                             :holding-days 200}]}
                  :tax-unit {:tax-residency :resident-individual}})
          iit   (component-by-lane facts :cn-iit-investment-income)]
      (is (some? iit))
      (is (== 10000M (-> iit :gross-liability :amount))
          "Post-sunset (period begins 2028-01-01): 50000 × 20 % = 10000"))))

;; ============================================================================
;; §4. Bank deposit + government bond interest — exempt, no component
;; ============================================================================

(deftest bank-deposit-interest-no-component
  (testing "bank deposit interest is exempt; provider emits no component"
    (let [conn (fresh)
          facts (run-iit conn
                         [{:event-id     "icbc-deposit"
                           :income-class :cn-bank-deposit-interest
                           :amount       5000M}]
                         {:tax-unit {:tax-residency :resident-individual}})]
      ;; A component MIGHT be emitted if there's nothing else, but its
      ;; gross-liability must be zero AND the only line should be the
      ;; exempt audit line.
      (let [iit (component-by-lane facts :cn-iit-investment-income)]
        (is (or (nil? iit)
                (== 0M (-> iit :gross-liability :amount)))
            "bank deposit interest contributes 0 to liability")))))

(deftest government-bond-interest-exempt
  (testing "government bond interest is exempt; no liability"
    (let [conn (fresh)
          facts (run-iit conn
                         [{:event-id     "moftreasury-1"
                           :income-class :government-bond-interest
                           :amount       20000M}]
                         {:tax-unit {:tax-residency :resident-individual}})
          iit   (component-by-lane facts :cn-iit-investment-income)]
      (is (or (nil? iit)
              (== 0M (-> iit :gross-liability :amount)))))))

;; ============================================================================
;; §5. Unlisted equity dividend — flat 20 % (no gradation)
;; ============================================================================

(deftest unlisted-equity-dividend-flat-20pct
  (testing "unlisted-equity dividend gets full flat 20 % — no gradation"
    (let [conn (fresh)
          facts (run-iit conn
                         [{:event-id     "privco-1"
                           :income-class :unlisted-equity-dividend
                           :amount       100000M
                           :holding-days 800}]  ; ignored — only A-share gradation
                         {:tax-unit {:tax-residency :resident-individual}})
          iit   (component-by-lane facts :cn-iit-investment-income)]
      (is (some? iit))
      (is (== 20000M (-> iit :gross-liability :amount))
          "unlisted equity dividend: 100000 × 20 % = 20000"))))

(deftest corporate-bond-interest-flat-20pct
  (testing "corporate bond interest: 20 % flat"
    (let [conn (fresh)
          facts (run-iit conn
                         [{:event-id     "shanghai-corp-bond-1"
                           :income-class :corporate-bond-interest
                           :amount       8000M}]
                         {:tax-unit {:tax-residency :resident-individual}})
          iit   (component-by-lane facts :cn-iit-investment-income)]
      (is (some? iit))
      (is (== 1600M (-> iit :gross-liability :amount))))))

;; ============================================================================
;; §6. Non-resident — 20 % outbound IIT WHT (individual)
;; ============================================================================

(deftest non-resident-individual-a-share-20pct
  (testing "non-resident individual A-share dividend: 20 % WHT (no gradation)"
    (let [conn (fresh)
          facts (run-iit conn
                         [{:event-id     "nr-a-share-1"
                           :income-class :listed-a-share-dividend
                           :amount       100000M
                           :holding-days 500}]    ; gradation does NOT apply to non-residents
                         {:tax-unit {:tax-residency :non-resident-individual}})
          iit   (component-by-lane facts :cn-iit-investment-income)]
      (is (some? iit))
      (is (== 20000M (-> iit :gross-liability :amount))
          "non-resident individual: 100k × 20 % = 20k (no Caishui [2015] 101 reduction)"))))

(deftest non-resident-individual-treaty-rate
  (testing "treaty-reduced rate applied to non-resident dividend"
    (let [conn (fresh)
          facts (run-iit conn
                         [{:event-id     "nr-treaty-1"
                           :income-class :listed-a-share-dividend
                           :amount       100000M
                           :holding-days 90}]
                         {:tax-unit {:tax-residency :non-resident-individual
                                     :treaty-rate   0.10M}})
          iit   (component-by-lane facts :cn-iit-investment-income)]
      (is (some? iit))
      (is (== 10000M (-> iit :gross-liability :amount))
          "treaty-reduced from 20 % to 10 %: 100k × 10 %"))))

;; ============================================================================
;; §7. EIT — §26(2) inter-TRR dividend exemption
;; ============================================================================

(deftest eit-unlisted-direct-tr-exempt
  (testing "Beijing Hightech unlisted direct holding → fully excluded (§26(2))"
    (let [conn (fresh)
          facts (run-eit conn
                         [{:event-id     "unlisted-a-1"
                           :income-class :unlisted-equity-dividend
                           :amount       10000000M
                           :holding-chain [:participation :issuer]}]
                         {:tax-unit {:tax-residency :resident-corporation}})
          eit   (component-by-lane facts :cn-eit-investment-income)]
      (is (some? eit))
      (is (= [10000000M] (get-in eit [:jurisdiction-specific-codes :cit-base-deductions]))
          "10M unlisted exempt under §26(2)"))))

(deftest eit-listed-gt-12m-exempt
  (testing "listed A-share holding > 12 months → exempt (§26(2) + Impl. Reg. §83)"
    (let [conn (fresh)
          facts (run-eit conn
                         [{:event-id     "ssa-b-1"
                           :income-class :listed-a-share-dividend
                           :amount       600000M
                           :holding-days 800
                           :holding-chain [:participation :issuer]}]
                         {:tax-unit {:tax-residency :resident-corporation}})
          eit   (component-by-lane facts :cn-eit-investment-income)]
      (is (some? eit))
      (is (= [600000M] (get-in eit [:jurisdiction-specific-codes :cit-base-deductions]))
          "600k listed > 12m → exempt"))))

(deftest eit-listed-lt-12m-included
  (testing "listed A-share holding < 12 months → INCLUDED to CIT base"
    (let [conn (fresh)
          facts (run-eit conn
                         [{:event-id     "ssa-c-1"
                           :income-class :listed-a-share-dividend
                           :amount       200000M
                           :holding-days 90  ; < 365 days
                           :holding-chain [:participation :issuer]}]
                         {:tax-unit {:tax-residency :resident-corporation}})
          ;; Look for the included-component (the non-§26(2)-eligible one)
          eit (->> (:components facts)
                   (filter #(seq (get-in % [:jurisdiction-specific-codes
                                            :cit-base-additions])))
                   first)]
      (is (some? eit))
      (is (= [200000M] (get-in eit [:jurisdiction-specific-codes :cit-base-additions]))
          "listed < 12m → 200k added to CIT base"))))

(deftest eit-stock-connect-gt-12m-exempt
  (testing "Stock Connect H-share > 12m → exempt under Caishui [2014] 81 §4"
    (let [conn (fresh)
          facts (run-eit conn
                         [{:event-id     "connect-d-1"
                           :income-class :stock-connect-h-share-dividend
                           :amount       730000M
                           :holding-days 540
                           :holding-chain [:participation :issuer]}]
                         {:tax-unit {:tax-residency :resident-corporation}})
          eit   (component-by-lane facts :cn-eit-investment-income)]
      (is (some? eit))
      (is (= [730000M] (get-in eit [:jurisdiction-specific-codes :cit-base-deductions]))
          "Stock Connect H-share > 12m → §26(2) excluded"))))

;; ============================================================================
;; §8. Partnership veil — Caishui [2008] 159 §4 — THE FOOTGUN
;; ============================================================================

(deftest eit-partnership-veil-denies-26-2
  (testing "partnership in :holding-chain DENIES §26(2) exemption even at TRR > 12m"
    (let [conn (fresh)
          facts (run-eit conn
                         [{:event-id     "partnership-allocated-1"
                           :income-class :unlisted-equity-dividend
                           :amount       5000000M
                           :holding-days 1500           ; long-held
                           :holding-chain [:participation :partnership-vehicle :issuer]}]
                         {:tax-unit {:tax-residency :resident-corporation}})
          eit (->> (:components facts)
                   (filter #(seq (get-in % [:jurisdiction-specific-codes
                                            :cit-base-additions])))
                   first)]
      (is (some? eit)
          "partnership-veil event should be INCLUDED in CIT base, not excluded")
      (is (= [5000000M] (get-in eit [:jurisdiction-specific-codes :cit-base-additions]))
          "5M dividend through partnership → 25 % EIT (§26(2) UNAVAILABLE)")
      ;; And NO :cit-base-deductions for this event.
      (is (every? #(empty? (get-in % [:jurisdiction-specific-codes :cit-base-deductions]))
                  (:components facts))
          "No §26(2) deduction emitted — partnership veil active"))))

(deftest eit-partnership-keyword-also-denies-26-2
  (testing ":partnership keyword (alternative spelling) ALSO blocks §26(2)"
    (let [conn (fresh)
          facts (run-eit conn
                         [{:event-id     "partnership-via-alt-1"
                           :income-class :unlisted-equity-dividend
                           :amount       3000000M
                           :holding-days 2000
                           :holding-chain [:participation :partnership :issuer]}]
                         {:tax-unit {:tax-residency :resident-corporation}})
          eit (->> (:components facts)
                   (filter #(seq (get-in % [:jurisdiction-specific-codes
                                            :cit-base-additions])))
                   first)]
      (is (some? eit)
          "partnership keyword also activates the veil")
      (is (= [3000000M] (get-in eit [:jurisdiction-specific-codes :cit-base-additions]))
          "3M dividend → CIT base (no §26(2))"))))

;; ============================================================================
;; §9. EIT — foreign source dividend with §23 FTC
;; ============================================================================

(deftest eit-foreign-corp-dividend-with-ftc
  (testing "foreign corp dividend → CIT base + §23 foreign tax credit"
    (let [conn (fresh)
          facts (run-eit conn
                         [{:event-id     "co-e-de-1"
                           :income-class :foreign-corp-dividend
                           :amount       380000M
                           :foreign-tax-paid 19000M
                           :holding-chain [:participation :issuer]}]
                         {:tax-unit {:tax-residency :resident-corporation}})
          eit (->> (:components facts)
                   (filter #(seq (get-in % [:jurisdiction-specific-codes
                                            :cit-base-additions])))
                   first)]
      (is (some? eit))
      (is (= [380000M] (get-in eit [:jurisdiction-specific-codes :cit-base-additions]))
          "foreign dividend included at CIT base")
      (let [ftc (get-in eit [:jurisdiction-specific-codes :cit-foreign-tax-credit])]
        (is (some? ftc))
        (is (= 19000M (-> ftc first :amount))
            "FTC of 19,000 (DE 5 % treaty WHT on EUR 50k = CNY 19k)")))))

;; ============================================================================
;; §10. EIT — non-resident outbound WHT (10 %)
;; ============================================================================

(deftest eit-non-resident-outbound-10pct
  (testing "non-resident corporate dividend: 10 % outbound WHT (Caishui [2008] 130)"
    (let [conn (fresh)
          facts (run-eit conn
                         [{:event-id     "outbound-1"
                           :income-class :unlisted-equity-dividend
                           :amount       1000000M}]
                         {:tax-unit {:tax-residency :non-resident-corporation}})
          wht (component-by-lane facts :cn-eit-outbound-wht)]
      (is (some? wht))
      (is (== 100000M (-> wht :gross-liability :amount))
          "10 % × 1M = 100,000 outbound WHT"))))

(deftest eit-non-resident-treaty-rate
  (testing "treaty-reduced rate (5 %) on outbound to substantial-holding shareholder"
    (let [conn (fresh)
          facts (run-eit conn
                         [{:event-id     "outbound-treaty-1"
                           :income-class :unlisted-equity-dividend
                           :amount       1000000M}]
                         {:tax-unit {:tax-residency :non-resident-corporation
                                     :treaty-rate   0.05M}})
          wht (component-by-lane facts :cn-eit-outbound-wht)]
      (is (some? wht))
      (is (== 50000M (-> wht :gross-liability :amount))
          "treaty 5 %: 1M × 5 % = 50,000"))))

;; ============================================================================
;; §11. Note 158 §2 ex B — Beijing Hightech mixed-event smoke test
;; ============================================================================

(deftest note-158-ex-b-mixed-corporate-dividends
  (testing "Beijing Hightech: 4 excluded + 1 included + foreign FTC"
    (let [conn (fresh)
          facts (run-eit
                 conn
                 [;; Event 1: 10M unlisted direct — exempt
                  {:event-id     "co-a-1" :income-class :unlisted-equity-dividend
                   :amount       10000000M
                   :holding-chain [:participation :issuer]}
                  ;; Event 2: 600k listed > 12m — exempt
                  {:event-id     "co-b-1" :income-class :listed-a-share-dividend
                   :amount       600000M  :holding-days 800
                   :holding-chain [:participation :issuer]}
                  ;; Event 3: 200k listed < 12m — INCLUDED
                  {:event-id     "co-c-1" :income-class :listed-a-share-dividend
                   :amount       200000M  :holding-days 90
                   :holding-chain [:participation :issuer]}
                  ;; Event 4: 730k Stock Connect > 12m — exempt
                  {:event-id     "co-d-1" :income-class :stock-connect-h-share-dividend
                   :amount       730000M  :holding-days 540
                   :holding-chain [:participation :issuer]}
                  ;; Event 5: 380k foreign with 19k FTC
                  {:event-id     "co-e-1" :income-class :foreign-corp-dividend
                   :amount       380000M  :foreign-tax-paid 19000M
                   :holding-chain [:participation :issuer]}]
                 {:tax-unit {:tax-residency :resident-corporation}})
          excluded-cmp  (component-by-lane facts :cn-eit-investment-income)
          domestic-cmp  (component-by-lane facts :cn-eit-domestic-included)
          foreign-cmp   (component-by-lane facts :cn-eit-foreign)
          excl-total    (first (get-in excluded-cmp
                                       [:jurisdiction-specific-codes :cit-base-deductions]))
          domestic-total (first (get-in domestic-cmp
                                        [:jurisdiction-specific-codes :cit-base-additions]))
          foreign-total  (first (get-in foreign-cmp
                                        [:jurisdiction-specific-codes :cit-base-additions]))]
      (is (some? excluded-cmp))
      (is (some? domestic-cmp))
      (is (some? foreign-cmp))
      (is (== 11330000M excl-total)
          "Excluded: 10M + 600k + 730k = 11.33M (per §2 ex B)")
      (is (== 200000M domestic-total)
          "Domestic non-qualifying: 200k (listed <12m)")
      (is (== 380000M foreign-total)
          "Foreign-source: 380k (§23 FTC eligible)")
      (let [ftc (get-in foreign-cmp [:jurisdiction-specific-codes :cit-foreign-tax-credit])]
        (is (= 19000M (-> ftc first :amount))
            "FTC = CNY 19,000 (only on the foreign component, per §23-24)")))))

;; ============================================================================
;; §12. IIT — paying-agent prepaid + foreign tax credit
;; ============================================================================

(deftest iit-with-paying-agent-prepaid
  (testing "withheld becomes prepaid; reduces net liability"
    (let [conn (fresh)
          facts (run-iit conn
                         [{:event-id     "privco-with-wht"
                           :income-class :unlisted-equity-dividend
                           :amount       100000M
                           :withheld     20000M}]
                         {:tax-unit {:tax-residency :resident-individual}})
          iit   (component-by-lane facts :cn-iit-investment-income)]
      (is (some? iit))
      (is (== 20000M (-> iit :gross-liability :amount)))
      (is (== 20000M (-> iit :prepaid :amount)))
      (is (== 0M (-> iit :liability :amount))
          "gross 20k − prepaid 20k = 0 residual"))))

(deftest iit-foreign-source-with-ftc
  (testing "foreign-source dividend with §7 IIT foreign tax credit"
    (let [conn (fresh)
          facts (run-iit conn
                         [{:event-id     "foreign-co-1"
                           :income-class :foreign-corp-dividend
                           :amount       100000M
                           :foreign-tax-paid 5000M}]
                         {:tax-unit {:tax-residency :resident-individual}})
          iit   (component-by-lane facts :cn-iit-investment-income)]
      (is (some? iit))
      ;; Flat 20 % × 100k = 20k; FTC 5k → liability 15k
      (is (== 20000M (-> iit :gross-liability :amount)))
      (is (== 15000M (-> iit :liability :amount))
          "gross 20k − FTC 5k = 15k net"))))

;; ============================================================================
;; §13. C-REIT distribution — no §26(2) for corporate, 20 % for individual
;; ============================================================================

(deftest c-reit-individual-20pct
  (testing "C-REIT distribution to individual: 20 % (no Caishui [2015] 101 gradation)"
    (let [conn (fresh)
          facts (run-iit conn
                         [{:event-id     "creit-1"
                           :income-class :c-reit-distribution
                           :amount       50000M}]
                         {:tax-unit {:tax-residency :resident-individual}})
          iit   (component-by-lane facts :cn-iit-investment-income)]
      (is (some? iit))
      (is (== 10000M (-> iit :gross-liability :amount))
          "50k × 20 %"))))

(deftest c-reit-corporate-no-26-2-exemption
  (testing "C-REIT distribution to corporation: §26(2) NOT applicable"
    (let [conn (fresh)
          facts (run-eit conn
                         [{:event-id     "creit-corp-1"
                           :income-class :c-reit-distribution
                           :amount       1000000M
                           :holding-days 2000
                           :holding-chain [:participation :issuer]}]
                         {:tax-unit {:tax-residency :resident-corporation}})
          included (->> (:components facts)
                        (filter #(seq (get-in % [:jurisdiction-specific-codes
                                                 :cit-base-additions])))
                        first)
          excluded (->> (:components facts)
                        (filter #(seq (get-in % [:jurisdiction-specific-codes
                                                 :cit-base-deductions])))
                        first)]
      (is (some? included)
          "C-REIT included in CIT base — §26(2) NOT available per 2022 Announcement §1")
      (is (nil? excluded)
          "No §26(2) exemption")
      (is (= [1000000M] (get-in included [:jurisdiction-specific-codes
                                          :cit-base-additions]))))))

(ns kontor.l10n-br.investment-income-provider-test
  "Tests for the BR investment-income provider (ADR-099 + ADR-101,
   research note 155). Coverage:

   §1 Pre-2026 PF dividend exemption (the Lei 9.249/95 grandfather).
   §2 Post-2026 PF dividend > R$ 50k/month → 10 % IRRF (Lei 15.270/2025
      art. 6).
   §3 Post-2026 cross-border dividend (any amount) → 10 % IRRF
      (art. 7).
   §4 IRPFM ramp boundaries — R$ 600k (0 %), R$ 900k (5 % midpoint),
      R$ 1.2M (10 %).
   §5 JCP pre/post-2026 rate cliff (15 % → 17.5 %).
   §6 Renda fixa — each of four rate bands.
   §7 FII conditional exemption.
   §8 PJ-to-PJ exemption preserved (no component).
   §9 Note 155 §2 worked examples — A (Sra. Costa mixed) + B
      (CorpD → LuxCo R$ 5M).
   §10 IRPFM credits drive payable down (the dividend-bunching anti-
       avoidance role)."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.l10n-br.investment-income-provider :as inv]
            [kontor.period-tax-provider :as ptp]))

;; ============================================================================
;; Fixture
;; ============================================================================

(defn- fresh
  "Fresh test DB with the BR investment-income statute + BRL commodity
   + one PF and one PJ entity."
  []
  (let [conn (core/create-test-db)]
    (inv/install-statute! conn)
    (d/transact conn [{:commodity/symbol    "BRL"
                       :commodity/name      "Brazilian Real"
                       :commodity/precision 2}
                      {:entity/code "PF-COSTA" :entity/name "Sra. Costa"
                       :entity/kind :person :entity/country "BR"
                       :entity/functional-commodity [:commodity/symbol "BRL"]}
                      {:entity/code "PJ-CORPD" :entity/name "CorpD S.A."
                       :entity/kind :company :entity/country "BR"
                       :entity/functional-commodity [:commodity/symbol "BRL"]}])
    conn))

(def ^:private p2026 {:from #inst "2026-01-01" :to #inst "2027-01-01"})
(def ^:private p2025 {:from #inst "2025-01-01" :to #inst "2025-12-31"})

(defn- run-pf
  "Build the PF provider and run it with the given inputs."
  [conn period inputs & [extra-ctx]]
  (let [provider (inv/br-individual-investment-income-provider {})]
    (ptp/period-tax-facts
     provider
     (merge {:db     (d/db conn)
             :entity [:entity/code "PF-COSTA"]
             :period period
             :inputs inputs}
            extra-ctx))))

(defn- run-pj
  [conn period inputs & [extra-ctx]]
  (let [provider (inv/br-corporate-investment-income-provider {})]
    (ptp/period-tax-facts
     provider
     (merge {:db     (d/db conn)
             :entity [:entity/code "PJ-CORPD"]
             :period period
             :inputs inputs}
            extra-ctx))))

(defn- component-by-lane
  [facts lane]
  (->> (:components facts)
       (filter #(= lane (get-in % [:jurisdiction-specific-codes :lane])))
       first))

;; ============================================================================
;; §1. Pre-2026 PF dividend exemption (Lei 9.249/95 art. 10)
;; ============================================================================

(deftest pre-2026-pf-dividend-exempt
  (testing "PF dividend in 2025 — even R$ 100k/month — is EXEMPT"
    (let [conn  (fresh)
          facts (run-pf conn p2025
                        {:br-dividend-per-payer-per-month
                         [{:payer "CorpA" :recipient "Costa"
                           :year 2025 :month 6 :amount 100000M
                           :foreign? false}]})
          div   (component-by-lane facts :br-dividend-irrf)]
      (is (some? div) "audit component emitted even at zero IRRF")
      (is (zero? (-> div :liability :amount))
          "pre-2026 cliff: dividend IRRF rate = 0 → no tax"))))

;; ============================================================================
;; §2. Post-2026 PF dividend > R$ 50k/month → 10 % IRRF
;; ============================================================================

(deftest post-2026-pf-dividend-over-trigger-10pct
  (testing "PF dividend R$ 60k/month from CorpA → 10 % IRRF on R$ 60k = R$ 6 000"
    (let [conn  (fresh)
          facts (run-pf conn p2026
                        {:br-dividend-per-payer-per-month
                         [{:payer "CorpA" :recipient "Costa"
                           :year 2026 :month 3 :amount 60000M
                           :foreign? false}]})
          div   (component-by-lane facts :br-dividend-irrf)]
      (is (some? div) "component is emitted")
      (is (== 6000M (-> div :liability :amount))
          "10 % × R$ 60 000 = R$ 6 000 IRRF"))))

(deftest post-2026-pf-dividend-under-trigger-no-tax
  (testing "PF dividend R$ 40k/month from CorpA → no IRRF (under R$ 50k trigger)"
    (let [conn  (fresh)
          facts (run-pf conn p2026
                        {:br-dividend-per-payer-per-month
                         [{:payer "CorpA" :recipient "Costa"
                           :year 2026 :month 3 :amount 40000M
                           :foreign? false}]})
          div   (component-by-lane facts :br-dividend-irrf)]
      (is (some? div) "audit trail still surfaces — component emitted")
      (is (zero? (-> div :liability :amount))
          "below R$ 50 000 → no IRRF"))))

(deftest post-2026-pf-multi-payer-split-each-below-trigger
  (testing "R$ 40k/month each from CorpA + CorpB → no IRRF (per-payer cap)"
    (let [conn  (fresh)
          facts (run-pf conn p2026
                        {:br-dividend-per-payer-per-month
                         [{:payer "CorpA" :recipient "Costa"
                           :year 2026 :month 3 :amount 40000M}
                          {:payer "CorpB" :recipient "Costa"
                           :year 2026 :month 3 :amount 40000M}]})
          div   (component-by-lane facts :br-dividend-irrf)]
      (is (zero? (-> div :liability :amount))
          "trigger is per-payer; multi-payer split escapes IRRF"))))

;; ============================================================================
;; §3. Post-2026 cross-border dividend (any amount) → 10 % IRRF
;; ============================================================================

(deftest post-2026-cross-border-dividend-any-amount
  (testing "any cross-border dividend → 10 % IRRF regardless of amount"
    (let [conn  (fresh)
          facts (run-pf conn p2026
                        {:br-dividend-per-payer-per-month
                         [{:payer "CorpD" :recipient "LuxCo"
                           :year 2026 :month 4 :amount 1000M
                           :foreign? true}]})
          div   (component-by-lane facts :br-dividend-irrf)]
      (is (== 100M (-> div :liability :amount))
          "10 % × R$ 1 000 = R$ 100 IRRF even below trigger"))))

(deftest grandfathered-dividend-skipped
  (testing "grandfathered? true → entry skipped entirely (2025 AGM-approval)"
    (let [conn  (fresh)
          facts (run-pf conn p2026
                        {:br-dividend-per-payer-per-month
                         [;; would-trigger but grandfathered → skipped
                          {:payer "CorpA" :recipient "Costa"
                           :year 2026 :month 2 :amount 80000M
                           :foreign? false :grandfathered? true}
                          ;; an under-trigger entry so we get an audit
                          ;; component to inspect
                          {:payer "CorpB" :recipient "Costa"
                           :year 2026 :month 2 :amount 10000M
                           :foreign? false}]})
          div   (component-by-lane facts :br-dividend-irrf)]
      (is (some? div))
      (is (zero? (-> div :liability :amount))
          "grandfathered entries do not trigger IRRF; only audit lines"))))

;; ============================================================================
;; §4. IRPFM ramp boundary cases
;; ============================================================================

(deftest irpfm-band-low-zero
  (testing "income at R$ 600 000 — IRPFM floor = 0"
    (let [conn  (fresh)
          facts (run-pf conn p2026
                        {:br-annual-income-base 600000M})
          irpfm (component-by-lane facts :br-irpfm)]
      (is (some? irpfm) "component is emitted")
      (is (zero? (-> irpfm :liability :amount))
          "ramp starts at R$ 600k — effective rate 0 % at the anchor")
      (is (zero? (-> irpfm :gross-liability :amount))
          "gross floor = 0 at the band-low anchor"))))

(deftest irpfm-midpoint-5pct
  (testing "income at R$ 900 000 — midpoint → effective rate 5 % → floor R$ 45 000"
    (let [conn  (fresh)
          facts (run-pf conn p2026
                        {:br-annual-income-base 900000M})
          irpfm (component-by-lane facts :br-irpfm)]
      (is (== 45000M (-> irpfm :gross-liability :amount))
          "5 % × R$ 900 000 = R$ 45 000 floor before credits")
      (is (== 45000M (-> irpfm :liability :amount))
          "no credits supplied → entire floor is payable"))))

(deftest irpfm-top-10pct
  (testing "income at R$ 1 200 000 — top → effective rate 10 % → floor R$ 120 000"
    (let [conn  (fresh)
          facts (run-pf conn p2026
                        {:br-annual-income-base 1200000M})
          irpfm (component-by-lane facts :br-irpfm)]
      (is (== 120000M (-> irpfm :gross-liability :amount))
          "10 % × R$ 1.2M = R$ 120 000")
      (is (== 120000M (-> irpfm :liability :amount))))))

(deftest irpfm-above-top-flat-10pct
  (testing "income at R$ 2 000 000 → still 10 % flat (above ramp top)"
    (let [conn  (fresh)
          facts (run-pf conn p2026
                        {:br-annual-income-base 2000000M})
          irpfm (component-by-lane facts :br-irpfm)]
      (is (== 200000M (-> irpfm :gross-liability :amount))
          "10 % × R$ 2M = R$ 200 000"))))

(deftest irpfm-credits-net-against-floor
  (testing "IRPFM credit-against-ordinary-IRPF: floor R$ 45 000 − R$ 30 000 credits = R$ 15 000"
    (let [conn  (fresh)
          facts (run-pf conn p2026
                        {:br-annual-income-base 900000M
                         :br-ordinary-irpf-paid 30000M})
          irpfm (component-by-lane facts :br-irpfm)]
      (is (== 45000M (-> irpfm :gross-liability :amount)))
      (is (== 15000M (-> irpfm :liability :amount))
          "credit nets against floor; payable = R$ 15 000"))))

(deftest irpfm-credits-exceed-floor-clamp-zero
  (testing "IRPFM credits ≥ floor → payable clamped to 0"
    (let [conn  (fresh)
          facts (run-pf conn p2026
                        {:br-annual-income-base 900000M
                         :br-ordinary-irpf-paid 100000M})
          irpfm (component-by-lane facts :br-irpfm)]
      (is (zero? (-> irpfm :liability :amount))
          "credits exceed floor → no refund, no payable"))))

;; ============================================================================
;; §5. JCP pre/post-2026 rate cliff
;; ============================================================================

(deftest jcp-pre-2026-rate-15pct
  (testing "JCP deliberated in 2025 (deliberation-date = 2025-12-15) → 15 %"
    (let [conn  (fresh)
          facts (run-pf conn p2026
                        {:br-jcp-summary {:amount 80000M
                                          :deliberation-date #inst "2025-12-15"}})
          jcp   (component-by-lane facts :br-jcp-irrf)]
      (is (== 12000M (-> jcp :liability :amount))
          "15 % × R$ 80 000 = R$ 12 000 (pre-cliff)"))))

(deftest jcp-post-2026-rate-17p5pct
  (testing "JCP deliberated 2026-03 → 17.5 %; R$ 80 000 → R$ 14 000"
    (let [conn  (fresh)
          facts (run-pf conn p2026
                        {:br-jcp-summary {:amount 80000M
                                          :deliberation-date #inst "2026-03-15"}})
          jcp   (component-by-lane facts :br-jcp-irrf)]
      (is (== 14000M (-> jcp :liability :amount))
          "17.5 % × R$ 80 000 = R$ 14 000 (post-cliff)"))))

;; ============================================================================
;; §6. Renda fixa — each of four rate bands
;; ============================================================================

(deftest renda-fixa-all-four-buckets
  (testing "one disposal in each bucket — all four rates fire"
    (let [conn  (fresh)
          facts (run-pf conn p2026
                        {:br-renda-fixa
                         [{:holding-days 100 :gain 1000M}    ; bucket 1 — 22.5%
                          {:holding-days 300 :gain 2000M}    ; bucket 2 — 20%
                          {:holding-days 500 :gain 4000M}    ; bucket 3 — 17.5%
                          {:holding-days 800 :gain 5000M}]}) ; bucket 4 — 15%
          rf    (component-by-lane facts :br-renda-fixa)
          ;; expected: 225 + 400 + 700 + 750 = 2075
          expected 2075M]
      (is (== expected (-> rf :liability :amount))
          (str "sum of per-bucket IRRF = " expected)))))

(deftest renda-fixa-boundary-cases
  (testing "exact boundary days fall in the LOWER bucket"
    (let [conn  (fresh)
          facts (run-pf conn p2026
                        {:br-renda-fixa
                         [{:holding-days 180 :gain 1000M}    ; still bucket 1 (≤180)
                          {:holding-days 360 :gain 1000M}    ; bucket 2 (≤360)
                          {:holding-days 720 :gain 1000M}    ; bucket 3 (≤720)
                          {:holding-days 721 :gain 1000M}]}) ; bucket 4 (>720)
          rf    (component-by-lane facts :br-renda-fixa)
          ;; expected: 225 + 200 + 175 + 150 = 750
          expected 750M]
      (is (== expected (-> rf :liability :amount))))))

;; ============================================================================
;; §7. FII conditional exemption
;; ============================================================================

(deftest fii-exempt-when-conditions-met
  (testing "FII with :conditions-met? true → no taxable component (audit only)"
    (let [conn  (fresh)
          facts (run-pf conn p2026
                        {:br-fii-distributions
                         [{:amount 50000M :conditions-met? true}]})
          rf    (component-by-lane facts :br-renda-fixa)]
      (is (or (nil? rf) (zero? (-> rf :liability :amount)))
          "exempt FII produces no taxable IRRF"))))

(deftest fii-falls-through-when-conditions-fail
  (testing "FII with :conditions-met? false → falls through to renda-fixa lane"
    (let [conn  (fresh)
          facts (run-pf conn p2026
                        {:br-fii-distributions
                         [{:amount 10000M :conditions-met? false
                           :holding-days 800}]})
          rf    (component-by-lane facts :br-renda-fixa)]
      (is (some? rf) "renda-fixa component emitted for non-exempt FII")
      (is (== 1500M (-> rf :liability :amount))
          "15 % × R$ 10 000 (bucket 4, >720d default) = R$ 1 500"))))

;; ============================================================================
;; §8. PJ-to-PJ exemption preserved (no component)
;; ============================================================================

(deftest pj-to-pj-domestic-dividends-exempt-no-component
  (testing "corporate provider with NO foreign dividends → empty components"
    (let [conn  (fresh)
          facts (run-pj conn p2026 {})]
      (is (empty? (:components facts))
          "PJ-to-PJ domestic is preserved-exempt → no component"))))

(deftest pj-foreign-dividends-fold-to-cit
  (testing "corporate provider with foreign dividends → :cit-base-additions"
    (let [conn  (fresh)
          facts (run-pj conn p2026
                        {:br-corp-foreign-dividends 500000M})
          fdiv  (component-by-lane facts :br-corp-foreign-div)]
      (is (some? fdiv) "component emitted")
      (is (zero? (-> fdiv :liability :amount))
          "no own liability — folds via :cit-base-additions")
      (is (= [500000M] (-> fdiv :jurisdiction-specific-codes :cit-base-additions))
          "the gross amount is added to CIT base"))))

;; ============================================================================
;; §9. Note 155 §2 — worked examples
;; ============================================================================

(deftest worked-example-a-sra-costa-mixed
  (testing
   "Sra. Costa 2026 (note 155 §2 Example A):
      - dividends from CorpA: R$ 60k/month × 12 → R$ 72 000 IRRF
      - dividends from CorpB: R$ 30k/month × 12 → R$ 0 IRRF
      - JCP from CorpC R$ 80 000 in March 2026 → R$ 14 000 IRRF
      - FII R$ 50 000 (conditions met) → exempt
      - CDB R$ 5 000 held 18 months (bucket 3) → R$ 875 IRRF
      - IRPFM base R$ 1 400 000, ordinary IRPF R$ 47 000
        → floor = R$ 140 000; credits = 47k + 72k + 14k = 133k
        → payable R$ 7 000"
    (let [conn (fresh)
          ;; build the 12-month CorpA + CorpB ledger
          corpa (for [m (range 1 13)]
                  {:payer "CorpA" :recipient "Costa"
                   :year 2026 :month m :amount 60000M :foreign? false})
          corpb (for [m (range 1 13)]
                  {:payer "CorpB" :recipient "Costa"
                   :year 2026 :month m :amount 30000M :foreign? false})
          facts (run-pf conn p2026
                        {:br-dividend-per-payer-per-month (vec (concat corpa corpb))
                         :br-jcp-summary       {:amount 80000M
                                                :deliberation-date #inst "2026-03-15"}
                         :br-renda-fixa        [{:holding-days 540 :gain 5000M}] ; bucket 3 (17.5%)
                         :br-fii-distributions [{:amount 50000M :conditions-met? true}]
                         :br-annual-income-base 1400000M
                         :br-ordinary-irpf-paid 47000M})
          div   (component-by-lane facts :br-dividend-irrf)
          jcp   (component-by-lane facts :br-jcp-irrf)
          rf    (component-by-lane facts :br-renda-fixa)
          irpfm (component-by-lane facts :br-irpfm)]
      (is (== 72000M (-> div :liability :amount))
          "CorpA: 12 × R$ 6 000 = R$ 72 000 IRRF")
      (is (== 14000M (-> jcp :liability :amount))
          "JCP at 2026 rate → R$ 14 000")
      (is (== 875M (-> rf :liability :amount))
          "CDB bucket 3 (17.5%) × R$ 5 000 = R$ 875")
      (is (== 140000M (-> irpfm :gross-liability :amount))
          "IRPFM floor = 10 % × R$ 1.4M = R$ 140 000")
      (is (== 7000M (-> irpfm :liability :amount))
          "payable = R$ 140 000 − R$ 133 000 credits = R$ 7 000"))))

(deftest worked-example-b-corpd-luxco-cross-border
  (testing
   "CorpD declares R$ 5 000 000 of dividends to LuxCo (note 155 §2 Ex. B):
      - 10 % IRRF on the full R$ 5M = R$ 500 000"
    (let [conn  (fresh)
          facts (run-pf conn p2026
                        {:br-dividend-per-payer-per-month
                         [{:payer "CorpD" :recipient "LuxCo"
                           :year 2026 :month 4 :amount 5000000M
                           :foreign? true}]})
          div   (component-by-lane facts :br-dividend-irrf)]
      (is (== 500000M (-> div :liability :amount))
          "10 % × R$ 5 000 000 = R$ 500 000 cross-border IRRF"))))

;; ============================================================================
;; §10. IRPFM dividend-bunching anti-avoidance role
;; ============================================================================

(deftest irpfm-catches-multi-payer-bunching
  (testing
   "PF receives R$ 40k/month from each of FIVE payers → no Pillar-1 IRRF
    (each payer below trigger) but IRPFM catches the R$ 2.4M aggregate."
    (let [conn (fresh)
          payers ["P1" "P2" "P3" "P4" "P5"]
          ledger (vec (for [p payers, m (range 1 13)]
                        {:payer p :recipient "Costa"
                         :year 2026 :month m :amount 40000M :foreign? false}))
          facts (run-pf conn p2026
                        {:br-dividend-per-payer-per-month ledger
                         :br-annual-income-base 2400000M})
          div   (component-by-lane facts :br-dividend-irrf)
          irpfm (component-by-lane facts :br-irpfm)]
      (is (zero? (-> div :liability :amount))
          "no per-payer trigger fires (each below R$ 50k/month)")
      (is (== 240000M (-> irpfm :liability :amount))
          "IRPFM floor 10 % × R$ 2.4M = R$ 240 000 (no credits)"))))

;; ============================================================================
;; §11. Constructor + validation
;; ============================================================================

(deftest constructor-validates-kind
  (testing "invalid :kind raises informative error"
    (let [conn (fresh)
          bad (inv/->BRInvestmentIncomeTaxProvider
               :bogus :br-rfb :BRL "" :bogus)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #":kind must be"
                            (ptp/period-tax-facts
                             bad {:db (d/db conn)
                                  :entity [:entity/code "PF-COSTA"]
                                  :period p2026
                                  :inputs {}}))))))

(deftest empty-inputs-individual-returns-no-components
  (testing "individual provider with no inputs and zero income → empty components"
    (let [conn  (fresh)
          facts (run-pf conn p2026 {})]
      (is (empty? (:components facts))))))

(ns kontor.l10n-uk.investment-income-provider-test
  "Tests for the UK investment-income provider.

   Worked examples covered:
     - §2.1 — C. Brown: £30k salary + £2,500 savings + £4,000 dividends
              (basic-rate filer) → £4,092.25 total (£300 sav + £306.25 div).
     - §2.2 — D. Patel: £60k salary + £600 savings + £3,000 dividends
              (higher-rate filer) → £883.75 inv tax (£40 sav + £843.75 div).
     - §2.4 — E. Wilson: £11k state pension + £8k savings (retiree corner)
              → £86 inv tax (£7,570 of savings absorbed by PA/SRS/PSA).
     - §2.3 — GreenCo Ltd corp Class 3 portfolio exemption → £0 liability."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.l10n-uk.investment-income-provider :as inv]
            [kontor.l10n-uk.investment-income-statute :as inv-statute]
            [kontor.tax.period-tax-provider :as ptp]))

;; ============================================================================
;; Fixture
;; ============================================================================

(defn- fresh
  "Fresh test DB with UK investment-income statute installed + a GBP commodity."
  []
  (let [conn (core/create-test-db)]
    (inv-statute/install! conn)
    (d/transact conn [{:kontor.commodity/symbol "GBP" :kontor.commodity/name "Pound sterling"
                       :kontor.commodity/precision 2}])
    conn))

;; Two periods to verify the April-2026 dividend rate cutover.
(def ^:private p2025
  "TY 2025/26 — last year of the 8.75/33.75/39.35 dividend rates."
  {:from #inst "2025-04-06" :to #inst "2026-04-05"})

(def ^:private p2026
  "TY 2026/27 — first year of the 10.75/35.75/39.35 dividend rates."
  {:from #inst "2026-04-06" :to #inst "2027-04-05"})

(defn- run-individual
  "Skip GL marginalize by passing pre-computed `:inputs
   :investment-income-bases`."
  [conn bases tax-unit & [extra-ctx]]
  (let [provider (inv/uk-individual-investment-income-provider {})]
    (ptp/period-tax-facts
     provider
     (merge {:db       (d/db conn)
             :entity   nil
             :period   p2025
             :tax-unit tax-unit
             :inputs   {:investment-income-bases bases}}
            extra-ctx))))

(defn- run-corporate
  [conn bases & [extra-ctx]]
  (let [provider (inv/uk-corporate-investment-income-provider {})]
    (ptp/period-tax-facts
     provider
     (merge {:db     (d/db conn)
             :entity nil
             :period p2025
             :inputs {:investment-income-bases bases}}
            extra-ctx))))

(defn- jsc [c k]
  (get-in c [:jurisdiction-specific-codes k]))

(defn- amt [m] (some-> m :amount))

;; ============================================================================
;; §1. Pure allocation — direct algorithm tests against §2 worked examples
;; ============================================================================

(def ^:private base-params
  {:pa                   12570M
   :srs                  5000M
   :psa-basic            1000M
   :psa-higher           500M
   :div-allowance        500M
   :basic-band           37700M
   :additional-threshold 125140M
   :basic-rate           0.20M
   :higher-rate          0.40M
   :additional-rate      0.45M
   :div-basic-rate       0.0875M
   :div-higher-rate      0.3375M
   :div-add-rate         0.3935M})

(deftest allocation-sec-2-1-basic-rate-three-compartment
  (testing "§2.1 C. Brown — £30k salary + £2,500 sav + £4,000 div, basic-rate"
    (let [r (inv/uk-income-tax-allocation
             {:non-savings 30000M :savings 2500M :dividends 4000M}
             base-params)]
      (is (= :basic (:marginal-rate-band r))      "basic-rate filer")
      (is (== 3486M (:non-savings-tax r))         "non-savings tax £3,486 (£17,430 × 20%)")
      (is (== 0M (:pa-leftover-to-savings r))     "PA fully consumed by non-savings")
      (is (== 0M (:srs-used r))                   "SRS tapered to 0 (non-savings above PA)")
      (is (== 1000M (:psa-used r))                "PSA basic £1,000 used")
      (is (== 300M (:savings-tax r))              "savings tax £300 (£1,500 × 20%)")
      (is (== 500M (:div-allowance-used r))       "dividend allowance £500 used")
      (is (== 306.25M (:dividend-tax r))          "dividend tax £306.25 (£3,500 × 8.75%)")
      (is (== 4092.25M (+ (:non-savings-tax r)
                          (:savings-tax r)
                          (:dividend-tax r)))     "total £4,092.25"))))

(deftest allocation-sec-2-2-higher-rate
  (testing "§2.2 D. Patel — £60k salary + £600 sav + £3,000 div, higher-rate"
    (let [r (inv/uk-income-tax-allocation
             {:non-savings 60000M :savings 600M :dividends 3000M}
             base-params)]
      (is (= :higher (:marginal-rate-band r))     "higher-rate filer")
      (is (== 11432M (:non-savings-tax r))        "non-savings tax £11,432 (basic + higher)")
      (is (== 500M (:psa-used r))                 "PSA higher £500 used")
      (is (== 40M (:savings-tax r))               "savings tax £40 (£100 × 40%)")
      (is (== 500M (:div-allowance-used r))       "dividend allowance used")
      (is (== 843.75M (:dividend-tax r))          "dividend tax £843.75 (£2,500 × 33.75%)")
      (is (== 12315.75M (+ (:non-savings-tax r)
                           (:savings-tax r)
                           (:dividend-tax r)))    "total £12,315.75"))))

(deftest allocation-sec-2-4-retiree-srs-active
  (testing "§2.4 E. Wilson — £11k pension + £8k savings (retiree corner)"
    (let [r (inv/uk-income-tax-allocation
             {:non-savings 11000M :savings 8000M :dividends 0M}
             base-params)]
      (is (= :basic (:marginal-rate-band r)))
      (is (== 0M (:non-savings-tax r))            "PA wipes non-savings entirely")
      (is (== 1570M (:pa-leftover-to-savings r))  "leftover PA £1,570 cascades to savings")
      (is (== 5000M (:srs-used r))                "full SRS £5,000 available + used")
      (is (== 1000M (:psa-used r))                "PSA £1,000 used")
      (is (== 86M (:savings-tax r))               "savings tax £86 (£430 × 20%)"))))

(deftest allocation-additional-rate-band
  (testing "additional-rate band kicks in above £125,140 total income"
    (let [r (inv/uk-income-tax-allocation
             {:non-savings 200000M :savings 0M :dividends 10000M}
             base-params)]
      (is (= :additional (:marginal-rate-band r)) "additional-rate filer")
      (is (== 0M (:psa-used r))                   "PSA = 0 for additional-rate")
      (is (== 500M (:div-allowance-used r))       "dividend allowance still applies")
      ;; Dividends sit entirely in the additional band (position is already
      ;; deep in the band): £9,500 × 39.35 % = £3,738.25
      (is (== 3738.25M (:dividend-tax r))         "dividends in additional band @ 39.35%"))))

(deftest allocation-isa-only-yields-zero-tax
  (testing "all income inside ISA wrapper → no taxable savings or dividends"
    (let [r (inv/uk-income-tax-allocation
             {:non-savings 50000M :savings 0M :dividends 0M}
             base-params)]
      (is (zero? (:savings-tax r)))
      (is (zero? (:dividend-tax r))))))

;; ============================================================================
;; §2. Pure-dividend rate ladders (pre + post Apr-2026 cutover)
;; ============================================================================

(deftest pure-dividend-basic-band-pre-2026
  (testing "single dividend at basic-band TY 2025/26 → 8.75% rate"
    (let [conn  (fresh)
          facts (run-individual conn
                                {:dividend-uk 4000M
                                 :savings-uk 0M
                                 :corp-dividend-exempt 0M
                                 :corp-dividend-taxable 0M}
                                {:non-savings-income 20000M})  ; basic-rate filer
          c     (first (:components facts))]
      (is (some? c))
      (is (= :investment-income-tax (:kind c)))
      (is (= :basic (jsc c :marginal-rate-band)))
      ;; allowance £500 + (3,500 × 8.75%) = £306.25
      (is (== 306.25M (amt (:liability c)))))))

(deftest pure-dividend-higher-band-pre-2026
  (testing "single dividend in higher band TY 2025/26 → 33.75% rate"
    (let [conn  (fresh)
          facts (run-individual conn
                                {:dividend-uk 10000M
                                 :savings-uk 0M
                                 :corp-dividend-exempt 0M
                                 :corp-dividend-taxable 0M}
                                {:non-savings-income 80000M})  ; deep in higher band
          c     (first (:components facts))]
      (is (= :higher (jsc c :marginal-rate-band)))
      ;; allowance £500 + (£9,500 × 33.75%) = £3,206.25
      (is (== 3206.25M (amt (:liability c)))))))

(deftest pure-dividend-additional-band-pre-2026
  (testing "single dividend in additional band TY 2025/26 → 39.35% rate"
    (let [conn  (fresh)
          facts (run-individual conn
                                {:dividend-uk 10000M
                                 :savings-uk 0M
                                 :corp-dividend-exempt 0M
                                 :corp-dividend-taxable 0M}
                                {:non-savings-income 200000M})
          c     (first (:components facts))]
      (is (= :additional (jsc c :marginal-rate-band)))
      ;; £9,500 × 39.35% = £3,738.25
      (is (== 3738.25M (amt (:liability c)))))))

(deftest pure-dividend-april-2026-rate-hike
  (testing "TY 2026/27 dividend rates jump to 10.75 / 35.75 / 39.35"
    (let [conn  (fresh)
          ;; Basic band in 2026/27
          fb    (run-individual conn
                                {:dividend-uk 4000M :savings-uk 0M
                                 :corp-dividend-exempt 0M :corp-dividend-taxable 0M}
                                {:non-savings-income 20000M}
                                {:period p2026})
          ;; Higher band in 2026/27
          fh    (run-individual conn
                                {:dividend-uk 10000M :savings-uk 0M
                                 :corp-dividend-exempt 0M :corp-dividend-taxable 0M}
                                {:non-savings-income 80000M}
                                {:period p2026})
          ;; Additional band in 2026/27 — unchanged at 39.35
          fa    (run-individual conn
                                {:dividend-uk 10000M :savings-uk 0M
                                 :corp-dividend-exempt 0M :corp-dividend-taxable 0M}
                                {:non-savings-income 200000M}
                                {:period p2026})]
      (is (== 376.25M (amt (:liability (first (:components fb)))))
          "basic dividend 2026/27: £3,500 × 10.75% = £376.25")
      (is (== 3396.25M (amt (:liability (first (:components fh)))))
          "higher dividend 2026/27: £9,500 × 35.75% = £3,396.25")
      (is (== 3738.25M (amt (:liability (first (:components fa)))))
          "additional dividend 2026/27 unchanged: £9,500 × 39.35% = £3,738.25"))))

;; ============================================================================
;; §3. Allowances — PSA, dividend allowance, SRS
;; ============================================================================

(deftest dividend-allowance-absorbs-first-500
  (testing "first £500 of dividends taxed at 0% (basic-rate filer)"
    (let [conn  (fresh)
          facts (run-individual conn
                                {:dividend-uk 500M :savings-uk 0M
                                 :corp-dividend-exempt 0M :corp-dividend-taxable 0M}
                                {:non-savings-income 20000M})
          c     (first (:components facts))]
      (is (== 0M (amt (:liability c))))
      (is (== 500M (jsc c :div-allowance-used))))))

(deftest psa-basic-absorbs-first-1000-savings
  (testing "first £1,000 of savings taxed at 0% for basic-rate filer"
    (let [conn  (fresh)
          facts (run-individual conn
                                {:dividend-uk 0M :savings-uk 1000M
                                 :corp-dividend-exempt 0M :corp-dividend-taxable 0M}
                                {:non-savings-income 20000M})
          c     (first (:components facts))]
      (is (== 0M (amt (:liability c))))
      (is (== 1000M (jsc c :psa-used))))))

(deftest psa-higher-only-500
  (testing "PSA shrinks to £500 for higher-rate filer"
    (let [conn  (fresh)
          facts (run-individual conn
                                {:dividend-uk 0M :savings-uk 600M
                                 :corp-dividend-exempt 0M :corp-dividend-taxable 0M}
                                {:non-savings-income 80000M})
          c     (first (:components facts))]
      ;; £500 @ 0%, £100 @ 40% = £40
      (is (== 40M (amt (:liability c))))
      (is (== 500M (jsc c :psa-used))))))

;; ============================================================================
;; §4. Three-compartment ordering test (the substrate's critical case)
;; ============================================================================

(deftest three-compartment-ordering-basic-filer
  (testing "£20k salary + £8k savings + £5k dividends, single basic-rate filer"
    (let [conn  (fresh)
          facts (run-individual conn
                                {:dividend-uk 5000M :savings-uk 8000M
                                 :corp-dividend-exempt 0M :corp-dividend-taxable 0M}
                                {:non-savings-income 20000M})
          c     (first (:components facts))]
      (is (= :basic (jsc c :marginal-rate-band))
          "total income £33k stays below £50,270 → basic-rate")
      ;; Salary £20k - PA £12,570 = £7,430 non-savings taxable.
      ;; SRS = max(0, 5000 - 7430) = 0 (non-savings above PA wipes SRS).
      ;; PSA basic = 1000. Savings zero-rate cap = 1000. Taxable savings = 7000.
      ;; Savings tax: position 7,430 → end 15,430, all basic. 7000 × 20% = 1400.
      ;; Dividend allowance 500. Taxable dividends 4500.
      ;; Dividend tax: position 7,430 + 8,000 = 15,430. all basic.
      ;;   4500 × 8.75% = 393.75
      ;; Total = 1400 + 393.75 = 1793.75
      (is (== 1793.75M (amt (:liability c))))
      (is (== 1400M (jsc c :savings-tax)))
      (is (== 393.75M (jsc c :dividend-tax))))))

;; ============================================================================
;; §5. ISA exemption — wrapped income filtered (substrate convention)
;; ============================================================================

(deftest isa-wrapped-income-yields-no-component
  (testing "an entity with ONLY ISA-wrapped income emits no component"
    (let [conn  (fresh)
          facts (run-individual conn
                                {:dividend-uk 0M :savings-uk 0M
                                 :corp-dividend-exempt 0M :corp-dividend-taxable 0M}
                                {:non-savings-income 50000M})]
      (is (empty? (:components facts))
          "no investment income outside ISA → empty components vec"))))

;; ============================================================================
;; §6. Corporate provider — CTA 2009 Part 9A exemption
;; ============================================================================

(deftest corp-part-9a-exempt-dividend
  (testing "GreenCo §2.3 — 4% portfolio holding qualifies for Class 3 exemption"
    (let [conn  (fresh)
          facts (run-corporate conn
                               {:corp-dividend-exempt 100000M
                                :corp-dividend-taxable 0M
                                :dividend-uk 0M :savings-uk 0M})
          c     (first (:components facts))]
      (is (some? c))
      (is (= :investment-income-tax (:kind c)))
      (is (== 0M (amt (:liability c)))     "Part 9A exemption → zero liability")
      (is (== 100000M (jsc c :exempt-amount))
          "gross £100k recorded in :exempt-amount for audit")
      (is (= [0M] (jsc c :cit-base-additions))
          "no addition to CT base"))))

(deftest corp-non-exempt-dividend-flows-to-cit
  (testing "non-exempt corporate dividend surfaces as :cit-base-additions"
    (let [conn  (fresh)
          facts (run-corporate conn
                               {:corp-dividend-exempt 0M
                                :corp-dividend-taxable 50000M
                                :dividend-uk 0M :savings-uk 0M})
          c     (first (:components facts))]
      (is (== 50000M (amt (:base c))))
      (is (== 0M (amt (:liability c)))     "provider doesn't compute CT")
      (is (= [50000M] (jsc c :cit-base-additions))
          "flows to consumer's CT provider"))))

(deftest corp-mixed-exempt-and-taxable
  (testing "corp with both exempt and non-exempt dividends emits one component"
    (let [conn  (fresh)
          facts (run-corporate conn
                               {:corp-dividend-exempt 80000M
                                :corp-dividend-taxable 20000M
                                :dividend-uk 0M :savings-uk 0M})
          c     (first (:components facts))]
      (is (== 20000M (jsc c :taxable-amount)))
      (is (== 80000M (jsc c :exempt-amount)))
      (is (= [20000M] (jsc c :cit-base-additions))))))

;; ============================================================================
;; §7. Component shape + composition checks
;; ============================================================================

(deftest components-use-investment-income-tax-kind
  (testing "all components carry :kind :investment-income-tax"
    (let [conn  (fresh)
          facts (run-individual conn
                                {:dividend-uk 3000M :savings-uk 2000M
                                 :corp-dividend-exempt 0M :corp-dividend-taxable 0M}
                                {:non-savings-income 30000M})]
      (is (every? #(= :investment-income-tax (:kind %)) (:components facts))))))

(deftest provider-kind-validation
  (testing "the provider rejects unknown :kind"
    (let [conn (fresh)
          bad (inv/->UKInvestmentIncomeTaxProvider :bogus :uk-hmrc :GBP "" :bogus)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #":kind must be"
                            (ptp/period-tax-facts
                             bad {:db (d/db conn) :period p2025
                                  :inputs {:investment-income-bases {}}}))))))

(deftest line-items-record-audit-trail
  (testing "the component's :line-items capture the §16 ordering decomposition"
    (let [conn  (fresh)
          facts (run-individual conn
                                {:dividend-uk 4000M :savings-uk 2500M
                                 :corp-dividend-exempt 0M :corp-dividend-taxable 0M}
                                {:non-savings-income 30000M})
          c     (first (:components facts))
          lines (set (map :line (:line-items c)))]
      (is (contains? lines :pa-against-non-savings))
      (is (contains? lines :pa-leftover))
      (is (contains? lines :srs-available))
      (is (contains? lines :psa))
      (is (contains? lines :savings-tax))
      (is (contains? lines :div-allowance-used))
      (is (contains? lines :dividend-tax))
      (is (contains? lines :marginal-rate-band)))))

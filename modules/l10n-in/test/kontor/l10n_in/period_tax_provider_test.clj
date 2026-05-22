(ns kontor.l10n-in.period-tax-provider-test
  "Stage 1 of the tax-completion program (research note 104) — Indian
   personal income tax, the deliberate dual-regime stress case.

   Golden values are cross-checked against the published FY 2025-26 /
   AY 2026-27 income-tax slabs (post Union Budget 2025)."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.book :as book]
            [kontor.core :as core]
            [kontor.l10n-in.period-tax-provider :as in]
            [kontor.period-tax-provider :as ptp]
            [kontor.tax-schedule :as ts]))

;; ============================================================================
;; Config assertions
;; ============================================================================

(deftest provider-config
  (testing "the new regime is the statutory default"
    (let [p (in/in-income-tax-provider {})]
      (is (= :new (:regime p)))
      (is (= :in-income-tax-new (:id p)))))
  (testing "both regimes resolve to an INR PeriodTaxProvider"
    (doseq [regime [:new :old]]
      (let [p (in/in-income-tax-provider {:regime regime})]
        (is (satisfies? ptp/PeriodTaxProvider p) (str regime))
        (is (= :INR (:commodity p)) (str regime))
        (is (= :in-income-tax-department (:authority p)) (str regime))
        (is (= :formula (:schedule/type (:schedule p)))
            (str regime " — the schedule is a :formula (the §87A rebate "
                 "is income-conditional, so not a static credit/surtax)")))))
  (testing "an unknown regime throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":regime must be"
                          (in/in-income-tax-provider {:regime :hybrid})))))

;; ============================================================================
;; New regime — §115BAC, the default
;; ============================================================================

(deftest new-regime-slabs
  (let [s (:schedule (in/in-income-tax-provider {:regime :new}))]
    (testing "the basic exemption — no tax up to ₹4,00,000"
      (is (zero? (ts/apply-schedule s 400000M)))
      (is (zero? (ts/apply-schedule s 300000M))))
    (testing "§87A — a total income up to ₹12,00,000 is effectively tax-free"
      ;; bracket tax at ₹12L = 5%·4L + 10%·4L = ₹60,000, fully rebated
      (is (zero? (ts/apply-schedule s 1200000M))
          "the headline '12 lakh tax-free' under the new regime")
      (is (zero? (ts/apply-schedule s 700000M))))
    (testing "§87A marginal relief just above ₹12,00,000"
      ;; bracket tax at ₹12.5L = 60000 + 15%·50000 = 67500; marginal
      ;; relief caps the tax at the income in excess of ₹12L = ₹50,000
      (is (== 50000M (ts/apply-schedule s 1250000M))
          "marginal relief — tax = income above the ₹12L threshold"))
    (testing "published worked examples — FY 2025-26 slabs"
      (is (== 120000M (ts/apply-schedule s 1600000M))
          "₹16L — 20000+40000+15%·4L = ₹1,20,000")
      (is (== 300000M (ts/apply-schedule s 2400000M))
          "₹24L — 20000+40000+60000+20%·4L+25%·4L = ₹3,00,000")
      (is (== 1080000M (ts/apply-schedule s 5000000M))
          "₹50L — top-slab tax, no surcharge at exactly ₹50L"))
    (testing "monotone increasing"
      (is (apply < (map #(ts/apply-schedule s %)
                        [1300000M 2000000M 3000000M 8000000M]))))))

(deftest new-regime-surcharge
  (let [s (:schedule (in/in-income-tax-provider {:regime :new}))]
    (testing "the surcharge applies above ₹50L, with marginal relief"
      ;; at ₹51L: post-rebate tax = 300000 + 30%·27L = ₹11,10,000;
      ;; raw 10% surcharge = 111000 → total 12,21,000; marginal relief
      ;; caps total at tax(₹50L)=10,80,000 + ₹1L income above = 11,80,000
      (is (== 1180000M (ts/apply-schedule s 5100000M))
          "marginal relief on the surcharge just above ₹50L"))
    (testing "the new regime caps the surcharge at 25 % (no 37 % band)"
      ;; ₹6Cr: post-rebate tax = 300000 + 30%·(6Cr−24L) = 1,75,80,000;
      ;; surcharge 25% (NOT 37%) = 43,95,000 → 2,19,75,000
      (is (== 21975000M (ts/apply-schedule s 60000000M))
          "§115BAC surcharge is capped at 25 %"))))

;; ============================================================================
;; Old regime — the optional regime (Form 10-IEA)
;; ============================================================================

(deftest old-regime-slabs
  (let [s (:schedule (in/in-income-tax-provider {:regime :old}))]
    (testing "the basic exemption — no tax up to ₹2,50,000"
      (is (zero? (ts/apply-schedule s 250000M)))
      (is (zero? (ts/apply-schedule s 200000M))))
    (testing "§87A — a total income up to ₹5,00,000 is tax-free"
      ;; bracket tax at ₹5L = 5%·2.5L = ₹12,500, fully rebated
      (is (zero? (ts/apply-schedule s 500000M))))
    (testing "the old-regime §87A is a HARD CLIFF — no marginal relief"
      ;; ₹5,10,000: bracket tax = 12500 + 20%·10000 = 14500; a rupee
      ;; above ₹5L forfeits the whole rebate, so the full ₹14,500 stands
      (is (== 14500M (ts/apply-schedule s 510000M))
          "above ₹5L the old-regime §87A rebate vanishes entirely"))
    (testing "published worked examples — old-regime slabs"
      (is (== 62500M (ts/apply-schedule s 750000M))
          "₹7.5L — 12500 + 20%·2.5L = ₹62,500")
      (is (== 112500M (ts/apply-schedule s 1000000M))
          "₹10L — 12500 + 20%·5L = ₹1,12,500")
      (is (== 262500M (ts/apply-schedule s 1500000M))
          "₹15L — 12500 + 100000 + 30%·5L = ₹2,62,500"))
    (testing "the old regime retains the 37 % top surcharge band"
      ;; ₹6Cr: bracket tax = 112500 + 30%·(6Cr−10L) = 1,78,12,500;
      ;; surcharge 37% = 65,90,625 → 2,44,03,125
      (is (== 24403125M (ts/apply-schedule s 60000000M))
          "old regime — 37 % surcharge above ₹5Cr"))))

;; ============================================================================
;; The cess — a 4 % surtax on both regimes
;; ============================================================================

(deftest health-education-cess
  (testing "4 % Health & Education cess as a computed surtax-fn"
    (let [conn (core/create-test-db)]
      (d/transact conn
                  [{:commodity/symbol "INR" :commodity/name "Indian Rupee"
                    :commodity/precision 2}
                   {:journal/code "SALE" :journal/type :sale}
                   {:account/path "Income:Salary" :account/type :income}
                   {:account/path "Assets:Bank"   :account/type :asset}])
      (book/sell! conn {:debit-account  [:account/path "Assets:Bank"]
                        :credit-account [:account/path "Income:Salary"]
                        :amount 2000000 :commodity [:commodity/symbol "INR"]
                        :effective-date #inst "2026-06-30"})
      (let [p     (in/in-income-tax-provider {:regime :new})
            facts (ptp/period-tax-facts
                   p
                   {:period {:from #inst "2026-04-01" :to #inst "2027-04-01"}
                    :conn   conn
                    :inputs {:base-transform
                             {:transform/type :adjustments
                              :deductions [in/new-regime-standard-deduction]}}})
            [c]   (:components facts)]
        ;; ₹20L gross − ₹75,000 standard deduction = ₹19,25,000 taxable.
        ;; New-regime tax = 20000+40000+60000+20%·325000 = ₹1,85,000.
        (is (== 1925000M (:amount (:base c))) "gross − standard deduction")
        (is (== 185000M (:amount (:gross-liability c)))
            "new-regime bracket tax on ₹19,25,000")
        (is (= 1 (count (:surtaxes c))) "the 4 % Health & Education cess")
        ;; cess = 4 % × 185000 = 7400; liability = 185000 + 7400
        (is (== 192400M (:amount (:liability c)))
            "income tax + 4 % cess")
        (is (= :new (:regime c))
            "the elected regime is recorded on the component")))))

;; ============================================================================
;; The dual regime — same income, the two regimes diverge
;; ============================================================================

(deftest dual-regime-election
  ;; ₹15,00,000 gross salary. The two regimes diverge in BOTH the
  ;; schedule AND the deduction base — exactly the abstraction stress
  ;; (note 104): they cannot be one :elect schedule over a shared base.
  (let [conn (core/create-test-db)
        _    (d/transact conn
                         [{:commodity/symbol "INR" :commodity/name "Rupee"
                           :commodity/precision 2}
                          {:journal/code "SALE" :journal/type :sale}
                          {:account/path "Income:Salary" :account/type :income}
                          {:account/path "Assets:Bank"   :account/type :asset}])
        _    (book/sell! conn {:debit-account  [:account/path "Assets:Bank"]
                               :credit-account [:account/path "Income:Salary"]
                               :amount 1500000 :commodity [:commodity/symbol "INR"]
                               :effective-date #inst "2026-06-30"})
        period {:from #inst "2026-04-01" :to #inst "2027-04-01"}
        facts  (fn [regime deductions]
                 (ptp/period-tax-facts
                  (in/in-income-tax-provider {:regime regime})
                  {:period period :conn conn
                   :inputs {:base-transform {:transform/type :adjustments
                                             :deductions deductions}}}))]
    (testing "new regime — only the ₹75,000 standard deduction"
      (let [[c] (:components (facts :new [in/new-regime-standard-deduction]))]
        ;; taxable ₹14,25,000 → 20000+40000+15%·225000 = 93750; cess → 97500
        (is (== 1425000M (:amount (:base c))))
        (is (== 93750M (:amount (:gross-liability c))))
        (is (== 97500M (:amount (:liability c))))
        (is (= :new (:regime c)))))
    (testing "old regime — ₹50,000 standard deduction + ₹1,50,000 §80C"
      (let [[c] (:components (facts :old [in/old-regime-standard-deduction
                                          150000M]))]
        ;; taxable ₹13,00,000 → 12500+100000+30%·3L = 202500; cess → 210600
        (is (== 1300000M (:amount (:base c)))
            "the old regime admits §80C — a deduction the new regime denies")
        (is (== 202500M (:amount (:gross-liability c))))
        (is (== 210600M (:amount (:liability c))))
        (is (= :old (:regime c)))))
    (testing "the regimes feed DIFFERENT bases — not an :elect over one base"
      ;; The whole point of approach (a): the bases differ (1425000 vs
      ;; 1300000), so the election cannot be a single :elect schedule.
      (is (not= (:amount (:base (first (:components
                                        (facts :new [in/new-regime-standard-deduction])))))
                (:amount (:base (first (:components
                                        (facts :old [in/old-regime-standard-deduction
                                                     150000M]))))))))))

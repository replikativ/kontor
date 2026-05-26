(ns kontor.l10n-in.period-tax-provider-test
  "Stage 1 of the tax-completion program (research note 104) — Indian
   personal income tax, the deliberate dual-regime stress case. Since
   note 105 frontier 1 (ADR-099 addendum 4) the §87A rebate and the
   surcharge are base-aware adjustment items, so these tests run the
   provider end to end and assert the final liability (income tax −
   §87A + surcharge + 4 % cess).

   Golden values are cross-checked against the published FY 2025-26 /
   AY 2026-27 income-tax slabs (post Union Budget 2025): each is the
   trusted pre-cess figure × 1.04."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.book :as book]
            [kontor.core :as core]
            [kontor.l10n-in.period-tax-provider :as in]
            [kontor.period-tax-provider :as ptp]
            [kontor.tax-schedule :as ts]))

;; ============================================================================
;; Config
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
        (is (= :progressive-bracket (:schedule/type (:schedule p)))
            (str regime " — the schedule is the plain bracket ladder; "
                 "§87A + surcharge are base-aware adjustment items"))
        (is (= 3 (count (:adjustments p)))
            (str regime " — §87A rebate, surcharge, cess")))))
  (testing "an unknown regime throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":regime must be"
                          (in/in-income-tax-provider {:regime :hybrid})))))

;; ============================================================================
;; The provider end to end
;; ============================================================================

(defn- fresh []
  (let [conn (core/create-test-db)]
    (d/transact conn
                [{:kontor.commodity/symbol "INR" :kontor.commodity/name "Indian Rupee"
                  :kontor.commodity/precision 2}
                 {:kontor.journal/code "SALE" :kontor.journal/type :sale}
                 {:kontor.account/path "Income:Salary" :kontor.account/type :income}
                 {:kontor.account/path "Assets:Bank"   :kontor.account/type :asset}])
    conn))

(defn- assess
  "Book `taxable` as income (no deductions) and run the IN provider for
   `regime`; return the `:personal-income-tax` component."
  [regime taxable]
  (let [conn (fresh)]
    (book/sell! conn {:debit-account  [:kontor.account/path "Assets:Bank"]
                      :credit-account [:kontor.account/path "Income:Salary"]
                      :amount taxable :commodity [:kontor.commodity/symbol "INR"]
                      :effective-date #inst "2026-06-30"})
    (first (:components
            (ptp/period-tax-facts
             (in/in-income-tax-provider {:regime regime})
             {:period {:from #inst "2026-04-01" :to #inst "2027-04-01"}
              :conn conn})))))

(defn- liability [regime taxable]
  (:amount (:liability (assess regime taxable))))

(deftest new-regime
  (testing "§87A — a total income up to ₹12,00,000 is effectively tax-free"
    (is (zero? (liability :new 1200000M))
        "the headline '12 lakh tax-free' under the new regime"))
  (testing "§87A marginal relief just above ₹12,00,000"
    ;; bracket tax ₹67,500; marginal relief caps the income tax at the
    ;; income above ₹12L = ₹50,000; + 4 % cess = ₹52,000.
    (is (== 52000M (liability :new 1250000M))))
  (testing "published worked examples (income tax + 4 % cess)"
    (is (== 124800M  (liability :new 1600000M)) "₹16L — 120000 + cess")
    (is (== 312000M  (liability :new 2400000M)) "₹24L — 300000 + cess")
    (is (== 1123200M (liability :new 5000000M)) "₹50L — 1080000 + cess"))
  (testing "the surcharge above ₹50L, with marginal relief"
    (is (== 1227200M (liability :new 5100000M))
        "₹51L — marginal relief on the surcharge, then cess"))
  (testing "§115BAC caps the surcharge at 25 % (no 37 % band)"
    (is (== 22854000M (liability :new 60000000M))
        "₹6Cr — 21975000 (25 % surcharge) + cess")))

(deftest old-regime
  (testing "§87A — a total income up to ₹5,00,000 is tax-free"
    (is (zero? (liability :old 500000M))))
  (testing "the old-regime §87A is a HARD CLIFF — no marginal relief"
    (is (== 15080M (liability :old 510000M))
        "₹5.1L — the rebate vanishes entirely; 14500 + cess"))
  (testing "published worked examples (income tax + 4 % cess)"
    (is (== 65000M  (liability :old 750000M))  "₹7.5L")
    (is (== 117000M (liability :old 1000000M)) "₹10L")
    (is (== 273000M (liability :old 1500000M)) "₹15L"))
  (testing "the old regime retains the 37 % top surcharge band"
    (is (== 25379250M (liability :old 60000000M))
        "₹6Cr — 24403125 (37 % surcharge) + cess")))

(deftest credits-and-surtaxes-are-structured
  ;; note 105 / ADR-099 addendum 4 — §87A and the surcharge are now
  ;; structured `:credits` / `:surtaxes`, not `:formula` internals.
  (testing "§87A surfaces as a structured :credit"
    (let [c      (assess :new 1200000M)
          rebate (first (filter #(= :87a-rebate (:code %)) (:credits c)))]
      (is (some? rebate) "the §87A rebate is a :credit item")
      (is (== 60000M (:amount (:amount rebate)))
          "the full ₹60,000 rebate at ₹12L")))
  (testing "the surcharge surfaces as a structured :surtax"
    (let [c   (assess :new 60000000M)
          sur (first (filter #(= :surcharge (:code %)) (:surtaxes c)))]
      (is (some? sur) "the surcharge is a :surtax item")
      (is (pos? (:amount (:amount sur))) "a positive surcharge at ₹6Cr"))))

;; ============================================================================
;; The cess — a 4 % surtax on both regimes
;; ============================================================================

(deftest health-education-cess
  (testing "4 % Health & Education cess in the adjustment layer"
    (let [conn (core/create-test-db)]
      (d/transact conn
                  [{:kontor.commodity/symbol "INR" :kontor.commodity/name "Indian Rupee"
                    :kontor.commodity/precision 2}
                   {:kontor.journal/code "SALE" :kontor.journal/type :sale}
                   {:kontor.account/path "Income:Salary" :kontor.account/type :income}
                   {:kontor.account/path "Assets:Bank"   :kontor.account/type :asset}])
      (book/sell! conn {:debit-account  [:kontor.account/path "Assets:Bank"]
                        :credit-account [:kontor.account/path "Income:Salary"]
                        :amount 2000000 :commodity [:kontor.commodity/symbol "INR"]
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
        ;; New-regime bracket tax = 20000+40000+60000+20%·325000 = ₹1,85,000.
        (is (== 1925000M (:amount (:base c))) "gross − standard deduction")
        (is (== 185000M (:amount (:gross-liability c)))
            "new-regime bracket tax on ₹19,25,000")
        (is (= 2 (count (:surtaxes c)))
            "the surcharge (₹0 here) + the 4 % Health & Education cess")
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
                         [{:kontor.commodity/symbol "INR" :kontor.commodity/name "Rupee"
                           :kontor.commodity/precision 2}
                          {:kontor.journal/code "SALE" :kontor.journal/type :sale}
                          {:kontor.account/path "Income:Salary" :kontor.account/type :income}
                          {:kontor.account/path "Assets:Bank"   :kontor.account/type :asset}])
        _    (book/sell! conn {:debit-account  [:kontor.account/path "Assets:Bank"]
                               :credit-account [:kontor.account/path "Income:Salary"]
                               :amount 1500000 :commodity [:kontor.commodity/symbol "INR"]
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
      (is (not= (:amount (:base (first (:components
                                        (facts :new [in/new-regime-standard-deduction])))))
                (:amount (:base (first (:components
                                        (facts :old [in/old-regime-standard-deduction
                                                     150000M]))))))))))

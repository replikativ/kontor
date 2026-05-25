(ns kontor.period-tax-provider-test
  "Iteration 1 of the period-tax build (ADR-099, research note 102) —
   the substrate: the `kontor.tax-schedule` algebra, the
   `PeriodTaxProvider` / `TaxReturnFacts` contract, and the
   `TaxReturnPostingBuilder`. A synthetic provider exercises the whole
   pipeline end to end — book → marginalize (σ_E base-selector) →
   schedule → TaxReturnFacts → provision posting → Ker σ → payment."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.book :as book]
            [kontor.core :as core]
            [kontor.money :as money]
            [kontor.period-tax-provider :as ptp]
            [kontor.report :as report]
            [kontor.tax-return-posting-builder :as trpb]
            [kontor.tax-schedule :as ts]
            [kontor.validation :as validation]))

;; ============================================================================
;; The schedule algebra
;; ============================================================================

(deftest schedule-base-shapes
  (testing ":flat"
    (is (== 200M (ts/apply-schedule (ts/flat 0.20M) 1000M))))
  (testing ":progressive-bracket — bracket fold"
    (let [s (ts/progressive [{:rate 0.10M :upper 1000M}
                             {:rate 0.20M :upper 5000M}
                             {:rate 0.30M :upper nil}])]
      (is (== 0M    (ts/apply-schedule s 0M)))
      (is (== 50M   (ts/apply-schedule s 500M))   "wholly in band 1")
      (is (== 100M  (ts/apply-schedule s 1000M))  "exactly the band-1 ceiling")
      (is (== 130M  (ts/apply-schedule s 1150M))  "100 + 30 — spills into band 2")
      (is (== 1500M (ts/apply-schedule s 7000M))  "100 + 800 + 600 — open top band")))
  (testing ":capped — rate on [floor, ceiling]"
    (let [s (ts/capped 0.06M {:floor 3500M :ceiling 68500M})]
      (is (== 0M    (ts/apply-schedule s 3500M))  "at the floor")
      (is (== 60M   (ts/apply-schedule s 4500M))  "1000 above the floor")
      (is (== 3900M (ts/apply-schedule s 99999M)) "capped at the ceiling")))
  (testing ":formula — escape hatch (fn is (fn [base ctx]))"
    (is (== 42M (ts/apply-schedule {:schedule/type :formula
                                    :fn (fn [_ _] 42M)} 999M))))
  (testing ":elect — same base, pick min/max"
    (is (== 250M (ts/apply-schedule {:schedule/type :elect :choose :min
                                     :schedules [(ts/flat 0.30M) (ts/flat 0.25M)]}
                                    1000M)))
    (is (== 300M (ts/apply-schedule {:schedule/type :elect :choose :max
                                     :schedules [(ts/flat 0.30M) (ts/flat 0.25M)]}
                                    1000M)))))

(deftest surtax-on-is-tax-on-a-tax
  ;; DE Solidaritätszuschlag — 5.5% of the income tax, not of income.
  (is (== 11M (ts/surtax-on 0.055M 200M))))

(deftest sum-combinator-adds-base-surcharges
  ;; AU — income-tax brackets + a 2% Medicare levy on the SAME base.
  (let [s (ts/sum-of [(ts/progressive [{:rate 0.10M :upper nil}])
                      (ts/flat 0.02M)])]
    (is (== 1200M (ts/apply-schedule s 10000M)) "10% bracket + 2% levy")))

(deftest unknown-schedule-throws
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown :schedule/type"
                        (ts/apply-schedule {:schedule/type :bogus} 100M))))

(deftest apply-adjustments-fold
  ;; note 105 frontier 1 — the ordered, signed, base-aware adjustment fold.
  (testing "a non-refundable credit floors the running tax at zero"
    (is (zero? (:liability (ts/apply-adjustments
                            100M [{:op :credit :code :c :amount 250M}] {})))))
  (testing "a refundable credit drives the result negative — a refund"
    (is (== -150M (:liability (ts/apply-adjustments
                               100M [{:op :credit :code :c :refundable? true
                                      :amount 250M}] {})))))
  (testing "a surtax adds"
    (is (== 1055M (:liability (ts/apply-adjustments
                               1000M [{:op :surtax :code :s :amount 55M}] {})))))
  (testing "a fn :amount sees the base and the running tax"
    (let [{:keys [liability resolved]}
          (ts/apply-adjustments
           1000M [{:op :credit :code :c
                   :amount (fn [{:keys [base running]}]
                             (* 0.10M (+ base running)))}]
           {:base 4000M})]
      (is (== 500M (:amount (first resolved))) "10% of (4000 base + 1000 running)")
      (is (== 500M liability))))
  (testing "order matters when a non-refundable credit floors"
    (is (== 100M (:liability (ts/apply-adjustments
                              1000M
                              [{:op :credit :code :c :amount 1200M}
                               {:op :surtax :code :s :amount 100M}] {})))
        "credit first floors to 0, then +100")
    (is (zero? (:liability (ts/apply-adjustments
                            1000M
                            [{:op :surtax :code :s :amount 100M}
                             {:op :credit :code :c :amount 1200M}] {})))
        "surtax first → 1100, then the 1200 credit floors to 0"))
  (testing "an unknown :op throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":op must be :credit"
                          (ts/apply-adjustments
                           100M [{:op :bogus :amount 5M}] {})))))

(deftest progressive-is-monotonic-in-base
  ;; a progressive schedule never decreases as the base grows.
  (let [s (ts/progressive [{:rate 0.15M :upper 50000M}
                           {:rate 0.30M :upper nil}])]
    (is (apply <= (map #(ts/apply-schedule s (bigdec %))
                       (range 0 1000000 25000))))))

(deftest one-bracket-progressive-equals-flat
  ;; a single open bracket is the degenerate :flat case.
  (let [prog (ts/progressive [{:rate 0.25M :upper nil}])
        flat (ts/flat 0.25M)]
    (is (every? (fn [base]
                  (== (ts/apply-schedule prog (bigdec base))
                      (ts/apply-schedule flat (bigdec base))))
                (range 0 1000000 50000)))))

;; ============================================================================
;; ADR-099 addendum — base transform + component combinators (note 103)
;; ============================================================================

(deftest base-transform-shapes
  (testing "nil / absent transform is the identity"
    (is (== 1000M (ts/apply-base-transform nil 1000M)))
    (is (== 1000M (ts/apply-base-transform {:transform/type :identity} 1000M))))
  (testing ":presumption-ratio — BR Lucro Presumido (32% of revenue)"
    (is (== 320M (ts/apply-base-transform
                  {:transform/type :presumption-ratio :ratio 0.32M} 1000M))))
  (testing ":adjustments — corporate book profit → taxable income"
    (is (== 1150M (ts/apply-base-transform
                   {:transform/type :adjustments
                    :additions [200M] :deductions [50M]} 1000M))
        "book 1000 + 200 add-back − 50 deduction"))
  (testing ":formula — escape hatch"
    (is (== 500M (ts/apply-base-transform
                  {:transform/type :formula :fn (fn [b] (* b 0.5M))} 1000M))))
  (testing "unknown :transform/type throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown :transform/type"
                          (ts/apply-base-transform {:transform/type :bogus} 1M)))))

(deftest component-combinators
  (testing "greater-of — the minimum-tax shape (regular tax vs MAT)"
    (is (== 500M (ts/greater-of 500M 300M)))
    (is (== 500M (ts/greater-of 300M 500M))))
  (testing "lesser-of — a liability cap"
    (is (== 300M (ts/lesser-of 500M 300M)))
    (is (== 300M (ts/lesser-of 300M 500M)))))

(deftest formula-schedule-receives-ctx
  ;; note 103 GAP 3 — the tax-unit reaches the schedule (the substrate seam
  ;; FR quotient familial / DE Ehegattensplitting need).
  (let [per-part {:schedule/type :formula
                  :fn (fn [base ctx] (/ base (:parts ctx)))}]
    (is (== 500M (ts/apply-schedule per-part 1000M {:parts 2M}))
        "the schedule reads :parts from ctx")
    (is (== 250M (ts/apply-schedule per-part 1000M {:parts 4M}))))
  (testing ":elect threads ctx into its sub-schedules"
    (let [s {:schedule/type :elect :choose :min
             :schedules [{:schedule/type :formula :fn (fn [base ctx]
                                                        (* base (:rate ctx)))}
                         (ts/flat 0.40M)]}]
      (is (== 200M (ts/apply-schedule s 1000M {:rate 0.20M}))
          "0.20 (from ctx) beats the flat 0.40"))))

;; ============================================================================
;; TaxReturnFacts helpers
;; ============================================================================

(defn- m [n] (money/money (bigdec n) :EUR))

(defn- facts-with [components]
  (ptp/tax-return-facts {:entity 1 :period {:from #inst "2026-01-01"
                                            :to #inst "2027-01-01"}
                         :jurisdiction {:country "XX"}
                         :functional-commodity :EUR
                         :components components}))

(deftest tax-return-facts-helpers
  (let [f (facts-with [{:kind :corporate-income-tax
                        :liability (m 150) :prepaid (m 40)}
                       {:kind :payroll-tax-employer
                        :liability (m 30) :prepaid (m 0)}])]
    (is (ptp/assessed? f))
    (is (== 180M (:amount (ptp/total-liability f))))
    (is (== 40M  (:amount (ptp/total-prepaid f))))
    (is (== 140M (:amount (ptp/balance f))) "180 owed − 40 prepaid"))
  (testing "no assessed liability"
    (is (not (ptp/assessed? (facts-with [{:kind :wealth-tax :liability (m 0)}])))))
  (testing "balance is negative when over-prepaid — a refund"
    (is (neg? (:amount (ptp/balance
                        (facts-with [{:kind :personal-income-tax
                                      :liability (m 100) :prepaid (m 250)}])))))))

(deftest valid-return-facts-is-the-closed-vocabulary-check
  (is (true?  (ptp/valid-return-facts?
               (facts-with [{:kind :personal-income-tax :liability (m 100)}]))))
  (is (false? (ptp/valid-return-facts?
               (facts-with [{:kind :not-a-real-tax :liability (m 100)}])))
      "a :kind outside period-tax-kinds fails — extend the enum by ADR")
  (is (false? (ptp/valid-return-facts?
               (facts-with [{:kind :wealth-tax}])))
      "a component with no :liability fails the structural check"))

;; ============================================================================
;; End to end — a synthetic PeriodTaxProvider through the whole pipeline
;; ============================================================================

(defn- fresh []
  (let [conn (core/create-test-db)]
    (d/transact conn
                [{:commodity/symbol "EUR" :commodity/name "Euro" :commodity/precision 2}
                 {:journal/code "SALE" :journal/type :sale}
                 {:journal/code "PUR"  :journal/type :purchase}
                 {:journal/code "GEN"  :journal/type :general}
                 {:account/path "Assets:Cash"          :account/type :asset}
                 {:account/path "Assets:Receivable"    :account/type :asset}
                 {:account/path "Income:Sales"         :account/type :income}
                 {:account/path "Expenses:Goods"       :account/type :expense}
                 {:account/path "Expenses:Income-Tax"  :account/type :expense}
                 {:account/path "Liabilities:Tax-Payable" :account/type :liability}])
    conn))

(def ^:private eur [:commodity/symbol "EUR"])
(def ^:private fy-2026 {:from #inst "2026-01-01" :to #inst "2027-01-01"})

(defn- synthetic-corp-tax-provider
  "A minimal PeriodTaxProvider: marginalize the period's income and
   expense (the σ_E base-selector), apply a flat corporate-tax rate."
  [rate]
  (reify ptp/PeriodTaxProvider
    (provider-id [_] :synthetic-corp)
    (period-tax-facts [_ {:keys [entity period conn]}]
      (let [postings (report/report-postings conn {:from (:from period)
                                                   :to   (:to period)})
            by-type  (report/marginalize postings :account-type {:sign :inflow})
            income   (get-in by-type [:income :value] (money/zero :EUR))
            expense  (get-in by-type [:expense :value] (money/zero :EUR))
            taxable  (money/sub income expense)
            gross    (ts/apply-schedule (ts/flat rate) (:amount taxable))]
        (ptp/tax-return-facts
         {:entity entity :period period
          :jurisdiction {:country "XX"}
          :functional-commodity :EUR
          :components [{:kind            :corporate-income-tax
                        :base            taxable
                        :schedule        (ts/flat rate)
                        :gross-liability (money/money gross :EUR)
                        :credits         []
                        :liability       (money/money gross :EUR)
                        :prepaid         (money/zero :EUR)
                        :provenance      {:provider-id :synthetic-corp}}]})))))

(defn- sum-account [conn path]
  (reduce + 0M
          (d/q '[:find [?amt ...] :in $ ?p
                 :where [?a :account/path ?p] [?pp :posting/account ?a]
                 [?pp :posting/amount ?amt]]
               (d/db conn) path)))

(deftest synthetic-provider-full-pipeline
  (let [conn (fresh)]
    ;; a year's trading — 1000 income, 400 expense → 600 taxable
    (book/sell! conn {:debit-account [:account/path "Assets:Receivable"]
                      :credit-account [:account/path "Income:Sales"]
                      :amount 1000 :commodity eur :effective-date #inst "2026-04-01"})
    (book/buy! conn {:debit-account [:account/path "Expenses:Goods"]
                     :credit-account [:account/path "Assets:Cash"]
                     :amount 400 :commodity eur :effective-date #inst "2026-05-01"})
    (let [provider (synthetic-corp-tax-provider 0.25M)
          builder  (trpb/make-static-tax-return-posting-builder
                    {:expense-account [:account/path "Expenses:Income-Tax"]
                     :payable-account [:account/path "Liabilities:Tax-Payable"]
                     :cash-account    [:account/path "Assets:Cash"]
                     :journal         [:journal/code "GEN"]
                     :commodity       eur})
          facts    (ptp/period-tax-facts provider {:entity 1 :period fy-2026 :conn conn})]
      (testing "the provider marginalizes the base and applies the schedule"
        (is (ptp/valid-return-facts? facts))
        (is (== 600M (:amount (:base (first (:components facts))))) "1000 − 400")
        (is (== 150M (:amount (ptp/total-liability facts))) "25% of 600"))
      (testing "the provision posts a balanced expense + payable transaction"
        (validation/transact-with-validation
         conn (trpb/provision-tx-data builder facts {:effective-date #inst "2026-12-31"}))
        (is (== 150M  (sum-account conn "Expenses:Income-Tax")) "Dr tax expense")
        (is (== -150M (sum-account conn "Liabilities:Tax-Payable")) "Cr tax payable"))
      (testing "the payment liquidates the payable"
        (validation/transact-with-validation
         conn (trpb/payment-tx-data builder facts
                                    {:amount 150 :date #inst "2027-02-15"} {}))
        (is (zero? (sum-account conn "Liabilities:Tax-Payable")) "payable cleared")))))

;; ============================================================================
;; S1 regression: FX on the tax-emission path (note 168 / ADR-099 addendum 3)
;; ============================================================================

(defn- const-fx-provider
  "Hand-rolled FxRateProvider returning a fixed CHF/EUR rate. Avoids the
   StaticTableProvider's db dependency for this kernel-level test."
  [rate]
  (reify kontor.fx_rate_provider.FxRateProvider
    (provider-id [_] :const-fx-test)
    (resolve-rate [_ {:keys [from-commodity to-commodity]}]
      (cond
        (= from-commodity to-commodity) 1M
        :else                            rate))
    (resolve-period-rates [_ _] {})))

(deftest translate-to-functional-identity-short-circuits
  (testing "When base commodity matches functional, no fx-provider needed"
    (let [ctx {:functional-commodity "EUR"
               :period {:to #inst "2026-12-31"}}
          eur-base (money/->Money 1000M "EUR")
          out      (ptp/translate-to-functional ctx eur-base)]
      (is (= eur-base out) "identity short-circuit returns input unchanged"))))

(deftest translate-to-functional-throws-without-fx-provider
  (testing "Missing :fx-provider on a foreign-currency base is a loud failure"
    (let [ctx {:functional-commodity "EUR"
               :period {:to #inst "2026-12-31"}}
          chf-base (money/->Money 1000M "CHF")]
      (try
        (ptp/translate-to-functional ctx chf-base)
        (is false "should have thrown")
        (catch clojure.lang.ExceptionInfo e
          (is (= :missing-fx-provider (:hint (ex-data e))))
          (is (= "CHF" (:from (ex-data e))))
          (is (= "EUR" (:to (ex-data e)))))))))

(deftest translate-to-functional-converts-when-provider-present
  (testing "CHF 1000 with 0.95 CHF/EUR rate → EUR 950 (HALF_EVEN, default 2dp)"
    (let [ctx {:functional-commodity "EUR"
               :period {:to #inst "2026-12-31"}
               :fx-provider (const-fx-provider 0.95M)}
          chf-base (money/->Money 1000M "CHF")
          out      (ptp/translate-to-functional ctx chf-base)]
      (is (= "EUR" (:commodity out)))
      (is (== 950M (:amount out)) "1000 × 0.95 = 950"))))

(deftest translate-amounts-to-functional-folds-mixed-commodities
  (testing "Multi-commodity balance summary collapses to one functional Money"
    (let [ctx {:functional-commodity "EUR"
               :period {:to #inst "2026-12-31"}
               :fx-provider (const-fx-provider 0.95M)}
          ;; EUR 500 + CHF 1000 @ 0.95 = EUR 500 + EUR 950 = EUR 1450
          summary {"EUR" 500M "CHF" 1000M}
          out     (ptp/translate-amounts-to-functional ctx summary)]
      (is (= "EUR" (:commodity out)))
      (is (== 1450M (:amount out))))))

(deftest monocommodity-facts?-true-for-uniform-functional-currency
  (testing "Facts whose every component's :base / :liability are in functional"
    (let [facts (ptp/tax-return-facts
                 {:entity 1 :period {:from #inst "2026-01-01" :to #inst "2026-12-31"}
                  :jurisdiction {:country :de}
                  :functional-commodity "EUR"
                  :components [{:kind :corporate-income-tax
                                :base (money/->Money 100000M "EUR")
                                :liability (money/->Money 15000M "EUR")}]})]
      (is (true? (ptp/monocommodity-facts? facts))))))

(deftest monocommodity-facts?-false-when-base-in-wrong-commodity
  ;; This is the silent-wrong case S1 documents: a provider that built a base
  ;; in CHF without translating to the EUR functional currency. The check
  ;; catches it post-construction.
  (testing "Facts with a CHF base under an EUR return → caught by checker"
    (let [bad (ptp/tax-return-facts
               {:entity 1 :period {:from #inst "2026-01-01" :to #inst "2026-12-31"}
                :jurisdiction {:country :de}
                :functional-commodity "EUR"
                :components [{:kind :corporate-income-tax
                              :base (money/->Money 100000M "CHF")  ; oops
                              :liability (money/->Money 15000M "EUR")}]})]
      (is (false? (ptp/monocommodity-facts? bad))
          "S1 regression: monocommodity-facts? surfaces the cross-currency leak"))))


(ns kontor.l10n-cn.period-tax-provider-test
  "CN period-tax providers — Enterprise Income Tax (企业所得税) and
   Individual Income Tax (个人所得税) on comprehensive income."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.book :as book]
            [kontor.core :as core]
            [kontor.l10n-cn.period-tax-provider :as cn]
            [kontor.period-tax-provider :as ptp]
            [kontor.tax-schedule :as ts]))

;; ============================================================================
;; 企业所得税 — Enterprise Income Tax
;; ============================================================================

(deftest cn-eit-rate
  (testing "the standard 25% rate"
    (is (= 0.25M (:rate (cn/cn-eit-provider {})))))
  (testing "the 15% High / New-Technology Enterprise rate"
    (is (= 0.15M (:rate (cn/cn-eit-provider {:hnte? true})))))
  (testing "an explicit :rate overrides (small-low-profit regimes)"
    (is (= 0.05M (:rate (cn/cn-eit-provider {:rate 0.05M})))))
  (let [p (cn/cn-eit-provider {})]
    (is (= :CNY (:commodity p)))
    (is (= :cn-eit (:id p)))))

;; ============================================================================
;; 个人所得税 — Individual Income Tax on comprehensive income (综合所得)
;; ============================================================================

(deftest cn-iit-config
  (let [p (cn/cn-iit-provider {})]
    (is (= :cn-iit (:id p)))
    (is (= :CNY (:commodity p)))
    (is (= :cn-tax (:authority p)))
    (is (= "中华人民共和国个人所得税法 §3 §6" (:statute p)))
    (is (= :progressive-bracket (:schedule/type (:schedule p)))))
  (testing "the 7-bracket annual comprehensive-income rate table"
    (let [bs cn/iit-comprehensive-income-brackets]
      (is (= 7 (count bs)))
      (is (= [0.03M 0.10M 0.20M 0.25M 0.30M 0.35M 0.45M]
             (mapv :rate bs)))
      (is (= [36000M 144000M 300000M 420000M 660000M 960000M nil]
             (mapv :upper bs)))))
  (testing "the ¥60,000 standard basic deduction"
    (is (= 60000M cn/iit-basic-deduction))))

(deftest cn-iit-golden-schedule
  ;; Golden values: `apply-schedule` on TAXABLE comprehensive income
  ;; (应纳税所得额 — already net of the basic + special additional
  ;; deductions). Cross-checked against the published 综合所得税率表一
  ;; via the quick-deduction (速算扣除数) method `rate × base − qd`.
  (let [s (:schedule (cn/cn-iit-provider {}))]
    (is (zero? (ts/apply-schedule s 0M))
        "no income, no tax")
    (is (== 1080M (ts/apply-schedule s 36000M))
        "top of band 1 — 36,000 × 3%")
    (is (== 2480M (ts/apply-schedule s 50000M))
        "band 2 — 1,080 + 14,000 × 10%  (= 50,000 × 10% − 2,520)")
    (is (== 11880M (ts/apply-schedule s 144000M))
        "top of band 2 — 144,000 × 10% − 2,520")
    (is (== 23080M (ts/apply-schedule s 200000M))
        "band 3 — 200,000 × 20% − 16,920")
    (is (== 250080M (ts/apply-schedule s 960000M))
        "top of band 6 — 960,000 × 35% − 85,920")
    (is (== 268080M (ts/apply-schedule s 1000000M))
        "band 7 — 1,000,000 × 45% − 181,920")))

(deftest cn-iit-base-transform
  (testing "the basic ¥60,000 deduction alone"
    (let [t (cn/iit-comprehensive-base-transform [])]
      (is (= :adjustments (:transform/type t)))
      (is (== 240000M (ts/apply-base-transform t 300000M))
          "300,000 gross − 60,000 basic")))
  (testing "the six special additional deductions ride alongside it"
    (let [t (cn/iit-comprehensive-base-transform
             [24000M    ; 子女教育 — children's education
              18000M])] ; 住房租金 — housing rent
      (is (== 198000M (ts/apply-base-transform t 300000M))
          "300,000 − 60,000 basic − 24,000 − 18,000"))))

;; ----------------------------------------------------------------------------
;; End-to-end annual reconciliation (年度汇算清缴)
;; ----------------------------------------------------------------------------

(def ^:private cny [:kontor.commodity/symbol "CNY"])
(def ^:private fy {:from #inst "2026-01-01" :to #inst "2027-01-01"})

(defn- fresh []
  (let [conn (core/create-test-db)]
    (d/transact conn
                [{:kontor.commodity/symbol "CNY" :kontor.commodity/name "Renminbi"
                  :kontor.commodity/precision 2}
                 {:journal/code "SALE" :journal/type :sale}
                 {:kontor.account/path "Income:Salary" :kontor.account/type :income}
                 {:kontor.account/path "Assets:Bank"   :kontor.account/type :asset}])
    conn))

(defn- book-income! [conn amount]
  (book/sell! conn {:debit-account  [:kontor.account/path "Assets:Bank"]
                    :credit-account [:kontor.account/path "Income:Salary"]
                    :amount amount :commodity cny
                    :effective-date #inst "2026-06-30"}))

(deftest cn-iit-annual-reconciliation
  (testing "marginalize gross income → deduct → schedule"
    (let [conn (fresh)]
      (book-income! conn 300000)
      (let [provider (cn/cn-iit-provider {})
            facts    (ptp/period-tax-facts
                      provider
                      {:period fy :conn conn
                       :inputs {:base-transform
                                (cn/iit-comprehensive-base-transform
                                 [24000M 18000M])}})
            [c]      (:components facts)]
        (is (ptp/valid-return-facts? facts))
        (is (= :personal-income-tax (:kind c)))
        (is (== 300000M (:amount (:value (first (filter #(= :gross-income (:line %))
                                                        (:line-items c))))))
            "gross comprehensive income, marginalized (σ_E)")
        (is (== 198000M (:amount (:base c)))
            "taxable income — 300,000 − 60,000 basic − 42,000 special")
        ;; 11,880 + (198,000 − 144,000) × 20% = 22,680
        (is (== 22680M (:amount (:liability c)))
            "annual IIT on 198,000 taxable comprehensive income")))))

(deftest cn-iit-annual-reconciliation-prepaid
  (testing "monthly cumulative withholding feeds the reconciliation via :prepaid"
    ;; The cumulative monthly withholding (累计预扣) rides `:inputs
    ;; :prepaid`; the liability stays the full annual IIT and `balance`
    ;; is the refund-or-top-up (ADR-099 addendum 3 — the `:prepaid`
    ;; path this provider first surfaced as a note-104 §4 finding).
    (let [conn (fresh)]
      (book-income! conn 300000)
      (let [provider (cn/cn-iit-provider {})
            facts    (ptp/period-tax-facts
                      provider
                      {:period fy :conn conn
                       :inputs {:base-transform
                                (cn/iit-comprehensive-base-transform
                                 [24000M 18000M])
                                :prepaid 20000M}})
            [c]      (:components facts)]
        (is (== 22680M (:amount (:liability c)))
            "the full annual IIT — withholding does NOT reduce it")
        (is (== 20000M (:amount (:prepaid c)))
            "the cumulative monthly withholding, recorded as :prepaid")
        (is (== 2680M (:amount (ptp/balance facts)))
            "residual to pay at reconciliation — 22,680 − 20,000 withheld")))))

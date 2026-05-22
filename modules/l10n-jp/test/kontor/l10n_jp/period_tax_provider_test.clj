(ns kontor.l10n-jp.period-tax-provider-test
  "Stage 1 (research note 104) — JP personal income tax period-tax
   providers: national 所得税 + the 2.1 % reconstruction surtax, and
   the local 住民税 inhabitant tax on a prior-year base.

   Golden values are cross-checked against the National Tax Agency's
   published 所得税の速算表 (income-tax quick-calculation table):

     taxable income     quick-table tax = income × rate − deduction
     ≤ 1,950,000        × 5%
     ≤ 3,300,000        × 10% −    97,500
     ≤ 6,950,000        × 20% −   427,500
     ≤ 9,000,000        × 23% −   636,000
     ≤ 18,000,000       × 33% − 1,536,000
     ≤ 40,000,000       × 40% − 2,796,000
     >  40,000,000      × 45% − 4,796,000"
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.book :as book]
            [kontor.core :as core]
            [kontor.l10n-jp.period-tax-provider :as jp]
            [kontor.period-tax-provider :as ptp]
            [kontor.tax-schedule :as ts]))

;; ============================================================================
;; National income tax — the 7-bracket schedule
;; ============================================================================

(deftest income-tax-schedule-matches-the-NTA-quick-table
  (let [s (:schedule (jp/jp-income-tax-provider {}))]
    (testing "the schedule shape"
      (is (= :progressive-bracket (:schedule/type s)))
      (is (= 7 (count (:brackets s))))
      (is (nil? (:upper (last (:brackets s)))) "open top band"))
    (testing "bracket-boundary values vs the published quick table"
      ;; 5% band top: 1,950,000 × 5% = 97,500
      (is (== 97500M (ts/apply-schedule s 1950000M)))
      ;; 10% band top: 3,300,000 × 10% − 97,500 = 232,500
      (is (== 232500M (ts/apply-schedule s 3300000M)))
      ;; 20% band top: 6,950,000 × 20% − 427,500 = 962,500
      (is (== 962500M (ts/apply-schedule s 6950000M)))
      ;; 23% band top: 9,000,000 × 23% − 636,000 = 1,434,000
      (is (== 1434000M (ts/apply-schedule s 9000000M)))
      ;; 33% band top: 18,000,000 × 33% − 1,536,000 = 4,404,000
      (is (== 4404000M (ts/apply-schedule s 18000000M)))
      ;; 40% band top: 40,000,000 × 40% − 2,796,000 = 13,204,000
      (is (== 13204000M (ts/apply-schedule s 40000000M))))
    (testing "mid-bracket values vs the quick table"
      ;; 5,000,000 × 20% − 427,500 = 572,500
      (is (== 572500M (ts/apply-schedule s 5000000M)))
      ;; 20,000,000 × 40% − 2,796,000 = 5,204,000
      (is (== 5204000M (ts/apply-schedule s 20000000M)))
      ;; 50,000,000 × 45% − 4,796,000 = 17,704,000
      (is (== 17704000M (ts/apply-schedule s 50000000M))))
    (testing "the schedule is monotone increasing and continuous"
      (is (apply < (map #(ts/apply-schedule s %)
                        [1000000M 3000000M 8000000M 30000000M 60000000M])))
      (doseq [b [1950000M 3300000M 6950000M 9000000M 18000000M 40000000M]]
        (is (>= 1M (- (ts/apply-schedule s (inc b))
                      (ts/apply-schedule s b)))
            (str "no jump at bracket boundary " b))))))

;; ============================================================================
;; Employment-income deduction (給与所得控除) — the :base-transform
;; ============================================================================

(deftest employment-income-deduction-matches-the-statute
  (testing "the 給与所得控除 schedule (所得税法 §28, 2020 reform)"
    ;; floor — ¥550,000 up to ¥1,625,000 gross
    (is (== 550000M (jp/employment-income-deduction 1000000M)))
    (is (== 550000M (jp/employment-income-deduction 1625000M)))
    ;; 1,800,000 × 40% − 100,000 = 620,000
    (is (== 620000M (jp/employment-income-deduction 1800000M)))
    ;; 3,000,000 × 30% + 80,000 = 980,000
    (is (== 980000M (jp/employment-income-deduction 3000000M)))
    ;; 5,000,000 × 20% + 440,000 = 1,440,000
    (is (== 1440000M (jp/employment-income-deduction 5000000M)))
    ;; 8,000,000 × 10% + 1,100,000 = 1,900,000
    (is (== 1900000M (jp/employment-income-deduction 8000000M)))
    ;; cap — ¥1,950,000 above ¥8,500,000 gross
    (is (== 1950000M (jp/employment-income-deduction 8500000M)))
    (is (== 1950000M (jp/employment-income-deduction 20000000M)))))

(deftest base-transform-subtracts-deduction-and-personal-deductions
  (testing "no personal deductions — just the 給与所得控除"
    (let [t (jp/employment-income-base-transform)]
      ;; gross 5,000,000 − 1,440,000 = 3,560,000 employment income
      (is (== 3560000M (ts/apply-base-transform t 5000000M)))))
  (testing "with personal deductions (所得控除) supplied via :inputs"
    (let [t (jp/employment-income-base-transform 480000M)]
      ;; gross 5,000,000 − 1,440,000 − 480,000 (basic deduction) = 3,080,000
      (is (== 3080000M (ts/apply-base-transform t 5000000M)))))
  (testing "taxable income floors at zero"
    (let [t (jp/employment-income-base-transform 5000000M)]
      (is (zero? (ts/apply-base-transform t 1000000M))))))

;; ============================================================================
;; Reconstruction surtax (復興特別所得税)
;; ============================================================================

(deftest reconstruction-surtax-is-2_1pct-of-the-income-tax
  (is (= 0.021M jp/reconstruction-surtax-rate))
  (let [item jp/reconstruction-surtax-adjustment]
    (is (= :reconstruction-surtax (:code item)))
    (is (= :surtax (:op item)))
    (testing "2.1% of the running national income tax"
      (is (== 21000M ((:amount item) {:running 1000000M}))
          "1,000,000 × 2.1% = 21,000"))
    (testing "zero surtax when there is no underlying tax"
      (is (zero? ((:amount item) {:running 0M}))))))

;; ============================================================================
;; National provider — config assertions
;; ============================================================================

(deftest income-tax-provider-config
  (let [p (jp/jp-income-tax-provider {})]
    (is (= :jp-shotokuzei (ptp/provider-id p)))
    (is (= :jp-nta (:authority p)))
    (is (= :JPY (:commodity p)))
    (is (= 1 (count (:adjustments p))) "the reconstruction surtax")))

;; ============================================================================
;; National provider — end-to-end against the posting kernel
;; ============================================================================

(defn- jpy-test-db []
  (let [conn (core/create-test-db)]
    (d/transact conn
                [{:commodity/symbol "JPY" :commodity/name "Japanese Yen"
                  :commodity/precision 0}
                 {:journal/code "SALE" :journal/type :sale}
                 {:account/path "Income:給料" :account/type :income}
                 {:account/path "Assets:銀行"  :account/type :asset}])
    conn))

(deftest income-tax-end-to-end-with-deduction-and-surtax
  (let [conn (jpy-test-db)]
    ;; a salaried worker earns ¥7,000,000 gross in 2026
    (book/sell! conn {:debit-account  [:account/path "Assets:銀行"]
                      :credit-account [:account/path "Income:給料"]
                      :amount 7000000 :commodity [:commodity/symbol "JPY"]
                      :effective-date #inst "2026-06-30"})
    (let [facts (ptp/period-tax-facts
                 (jp/jp-income-tax-provider {})
                 {:period {:from #inst "2026-01-01" :to #inst "2027-01-01"}
                  :conn   conn
                  ;; 給与所得控除 + ¥480,000 basic deduction
                  :inputs {:base-transform
                           (jp/employment-income-base-transform 480000M)}})
          [c]   (:components facts)]
      (testing "taxable income = 7,000,000 − 1,800,000 − 480,000 = 4,720,000"
        ;; 給与所得控除 at 7,000,000 = 7,000,000×10% + 1,100,000 = 1,800,000
        (is (== 4720000M (:amount (:base c)))))
      (testing "所得税 quick table: 4,720,000 × 20% − 427,500 = 516,500"
        (is (== 516500M (:amount (:gross-liability c)))))
      (testing "復興特別所得税 = 516,500 × 2.1% = 10,846.5"
        (is (= 1 (count (:surtaxes c))))
        (is (= :reconstruction-surtax (:code (first (:surtaxes c))))))
      (testing "total liability = 516,500 + 10,846.5 = 527,346.5"
        (is (== 527346.5M (:amount (:liability c))))))))

;; ============================================================================
;; Local inhabitant tax (住民税) — the prior-year base via :base-period
;; ============================================================================

(deftest inhabitant-tax-config
  (let [p (jp/jp-inhabitant-tax-provider {})]
    (is (= :jp-juminzei (ptp/provider-id p)))
    (is (= :jp-municipality (:authority p)))
    (is (= :JPY (:commodity p))))
  (testing "the standard 10% income levy splits 6% municipal + 4% prefectural"
    (is (= 0.10M jp/inhabitant-tax-income-rate))
    (is (= jp/inhabitant-tax-income-rate
           (+ jp/inhabitant-tax-municipal-rate
              jp/inhabitant-tax-prefectural-rate))))
  (testing "the per-capita levy is the fixed ¥6,000 均等割"
    (is (= 6000M jp/inhabitant-tax-per-capita-levy))))

(deftest inhabitant-tax-schedule-is-10pct-plus-per-capita-levy
  (let [s (jp/inhabitant-tax-schedule)]
    (testing "income levy + per-capita levy"
      ;; 3,000,000 × 10% + 6,000 = 306,000
      (is (== 306000M (ts/apply-schedule s 3000000M)))
      ;; 5,000,000 × 10% + 6,000 = 506,000
      (is (== 506000M (ts/apply-schedule s 5000000M))))
    (testing "no levy at all on zero taxable income"
      (is (zero? (ts/apply-schedule s 0M))))))

(deftest inhabitant-tax-assesses-the-PRIOR-year-via-base-period
  (let [conn (jpy-test-db)]
    ;; income is earned in 2025 …
    (book/sell! conn {:debit-account  [:account/path "Assets:銀行"]
                      :credit-account [:account/path "Income:給料"]
                      :amount 4000000 :commodity [:commodity/symbol "JPY"]
                      :effective-date #inst "2025-09-30"})
    ;; … and the 住民税 is BILLED across the 2026 fiscal year.
    (let [facts (ptp/period-tax-facts
                 (jp/jp-inhabitant-tax-provider {})
                 {;; the billing/assessment window — fiscal 2026
                  :period      {:from #inst "2026-06-01" :to #inst "2027-06-01"}
                  ;; the INCOME window — the prior calendar year 2025
                  :base-period {:from #inst "2025-01-01" :to #inst "2026-01-01"}
                  :conn        conn
                  :inputs      {:base-transform
                                (jp/employment-income-base-transform 480000M)}})
          [c]   (:components facts)]
      (testing "the base is marginalized over the PRIOR-year window"
        ;; 給与所得控除 at 4,000,000 = 4,000,000×20% + 440,000 = 1,240,000
        ;; taxable = 4,000,000 − 1,240,000 − 480,000 = 2,280,000
        (is (== 2280000M (:amount (:base c)))
            "income from base-period 2025, not the empty period 2026"))
      (testing "住民税 = 2,280,000 × 10% + 6,000 = 234,000"
        (is (== 234000M (:amount (:liability c)))))
      (testing "the liability :period stays the billing year"
        (is (= #inst "2026-06-01" (:from (:period facts)))))
      (testing "provenance records the base window used"
        (is (= {:from #inst "2025-01-01" :to #inst "2026-01-01"}
               (:base-period (:provenance c))))))))

(deftest inhabitant-tax-falls-back-to-period-when-no-base-period
  ;; A consumer that does not separate the windows still gets a
  ;; sane result — the base window defaults to :period.
  (let [conn (jpy-test-db)]
    (book/sell! conn {:debit-account  [:account/path "Assets:銀行"]
                      :credit-account [:account/path "Income:給料"]
                      :amount 3000000 :commodity [:commodity/symbol "JPY"]
                      :effective-date #inst "2026-03-31"})
    (let [facts (ptp/period-tax-facts
                 (jp/jp-inhabitant-tax-provider {})
                 {:period {:from #inst "2026-01-01" :to #inst "2027-01-01"}
                  :conn   conn
                  :inputs {:base-transform
                           (jp/employment-income-base-transform 480000M)}})
          [c]   (:components facts)]
      ;; 給与所得控除 at 3,000,000 = 3,000,000×30% + 80,000 = 980,000
      ;; taxable = 3,000,000 − 980,000 − 480,000 = 1,540,000
      ;; 住民税 = 1,540,000 × 10% + 6,000 = 160,000
      (is (== 160000M (:amount (:liability c)))))))

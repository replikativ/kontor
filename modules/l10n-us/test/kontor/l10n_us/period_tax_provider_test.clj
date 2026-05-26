(ns kontor.l10n-us.period-tax-provider-test
  "US federal income tax providers — Form 1120 (corporate, iteration 4)
   and Form 1040 (personal, note-104 Stage 1)."
  (:require [clojure.test :refer [deftest is testing]]
            [kontor.l10n-us.period-tax-provider :as us]
            [kontor.tax-schedule :as ts]))

;; ============================================================================
;; Form 1120 — corporate income tax
;; ============================================================================

(deftest us-corporate-income-tax-is-flat-21pct
  (let [p (us/us-corporate-income-tax-provider {})]
    (is (= 0.21M (:rate p)) "IRC §11 flat 21%")
    (is (= :USD (:commodity p)))
    (is (= :us-1120 (:id p)))
    (is (= :us-irs (:authority p)))
    (is (nil? (:minimum-tax p)) "CAMT is a later iteration"))
  (testing "an explicit :rate overrides"
    (is (= 0.18M (:rate (us/us-corporate-income-tax-provider {:rate 0.18M}))))))

;; ============================================================================
;; Form 1040 — personal income tax
;; ============================================================================

(deftest us-personal-income-tax-provider-config
  (let [p (us/us-personal-income-tax-provider {})]
    (is (= :us-1040 (:id p)))
    (is (= :us-irs (:authority p)))
    (is (= :USD (:commodity p)))
    (is (= "IRC §1" (:statute p)))
    (is (= :formula (get-in p [:schedule :kontor.schedule/type]))
        "filing-status selection needs the :formula escape hatch")
    (is (empty? (:surtax-fns p)) "no federal income-tax surtax modelled")))

(deftest filing-status-tables-are-well-formed
  (testing "every filing status has seven brackets with the open top band"
    (doseq [fs us/filing-statuses
            :let [bs (get us/filing-status-brackets-2024 fs)]]
      (is (= 7 (count bs)) fs)
      (is (nil? (:upper (last bs))) (str fs " — top band is open"))
      (is (= [0.10M 0.12M 0.22M 0.24M 0.32M 0.35M 0.37M]
             (mapv :rate bs))
          (str fs " — IRC §1(j) ordinary rates"))))
  (testing "every filing status has a standard deduction"
    (is (= us/filing-statuses (set (keys us/standard-deduction-2024))))))

;; --- Golden bracket-schedule values --------------------------------------
;; Tax computed directly on taxable income (deduction already taken) —
;; cross-checked against the published IRS 2024 rate schedules. The
;; schedule fn takes gross income and the :tax-unit ctx; here we pass
;; :itemized? true so the standard deduction is NOT re-applied and the
;; figure is the pure bracket tax on the given taxable income.

(defn- tax-on
  "Run `taxable` through the provider schedule for `filing-status`,
   suppressing the standard deduction (`:itemized? true`) so the
   result is the pure bracket tax on `taxable`."
  [filing-status taxable]
  (ts/apply-schedule
   (:schedule (us/us-personal-income-tax-provider {}))
   taxable
   {:tax-unit {:filing-status filing-status :itemized? true}}))

(deftest golden-bracket-tax-single
  (testing "single filer — IRS 2024 rate schedule"
    (is (== 1000.00M     (tax-on :single 10000M)))
    (is (== 6053.00M     (tax-on :single 50000M)))
    (is (== 17053.00M    (tax-on :single 100000M)))
    (is (== 145374.75M   (tax-on :single 500000M)))
    (is (== 328187.75M   (tax-on :single 1000000M)))))

(deftest golden-bracket-tax-married-filing-jointly
  (testing "married filing jointly — wider bands than single"
    (is (== 1000.00M     (tax-on :married-filing-jointly 10000M)))
    (is (== 5536.00M     (tax-on :married-filing-jointly 50000M)))
    (is (== 12106.00M    (tax-on :married-filing-jointly 100000M)))
    (is (== 115749.50M   (tax-on :married-filing-jointly 500000M)))
    (is (== 296125.50M   (tax-on :married-filing-jointly 1000000M)))))

(deftest golden-bracket-tax-married-filing-separately
  (testing "married filing separately — MFJ thresholds halved"
    (is (== 1000.00M     (tax-on :married-filing-separately 10000M)))
    (is (== 6053.00M     (tax-on :married-filing-separately 50000M))
        "= the single figure in the lower bands")
    (is (== 17053.00M    (tax-on :married-filing-separately 100000M)))
    (is (== 148062.75M   (tax-on :married-filing-separately 500000M))
        "diverges from single — the 35% band ends at 365,600 not 609,350")
    (is (== 333062.75M   (tax-on :married-filing-separately 1000000M)))))

(deftest golden-bracket-tax-head-of-household
  (testing "head of household — bands between single and MFJ"
    (is (== 1000.00M     (tax-on :head-of-household 10000M)))
    (is (== 5669.00M     (tax-on :head-of-household 50000M)))
    (is (== 15359.00M    (tax-on :head-of-household 100000M)))
    (is (== 143682.00M   (tax-on :head-of-household 500000M)))
    (is (== 326495.00M   (tax-on :head-of-household 1000000M)))))

;; --- Standard deduction applied by the provider --------------------------
;; Without :itemized?, the schedule subtracts the filing-status standard
;; deduction from gross income before running the brackets.

(deftest standard-deduction-is-applied-by-default
  (let [s (:schedule (us/us-personal-income-tax-provider {}))]
    (testing "single — $60,000 gross, std deduction $14,600 → taxable $45,400"
      (is (== 5216.00M
              (ts/apply-schedule s 60000M
                                 {:tax-unit {:filing-status :single}}))))
    (testing "MFJ — $120,000 gross, std deduction $29,200 → taxable $90,800"
      (is (== 10432.00M
              (ts/apply-schedule
               s 120000M
               {:tax-unit {:filing-status :married-filing-jointly}}))))
    (testing "HoH — $80,000 gross, std deduction $21,900 → taxable $58,100"
      (is (== 6641.00M
              (ts/apply-schedule
               s 80000M
               {:tax-unit {:filing-status :head-of-household}}))))
    (testing "gross income below the standard deduction → zero tax"
      (is (== 0M (ts/apply-schedule s 10000M
                                    {:tax-unit {:filing-status :single}}))))
    (testing ":itemized? true suppresses the standard deduction"
      (is (== 6053.00M
              (ts/apply-schedule
               s 50000M
               {:tax-unit {:filing-status :single :itemized? true}}))
          "the full $50,000 is taxed, no $14,600 subtracted")
      (is (== 4016.00M
              (ts/apply-schedule
               s 50000M
               {:tax-unit {:filing-status :single :itemized? false}}))
          "without :itemized? the $14,600 deduction is taken → taxable $35,400"))))

(deftest filing-status-defaults-and-validation
  (let [s (:schedule (us/us-personal-income-tax-provider {}))]
    (testing "no :tax-unit defaults to single"
      (is (== (ts/apply-schedule s 60000M {:tax-unit {:filing-status :single}})
              (ts/apply-schedule s 60000M nil))))
    (testing "an unknown filing status throws rather than mis-taxing"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"unknown :filing-status"
           (ts/apply-schedule s 60000M
                              {:tax-unit {:filing-status :bogus}}))))))

(ns kontor.payroll-jp.e2e-test
  "End-to-end JP payroll flow per ADR-084 §10.3. One Acme株式会社 (JP KK)
   with three hires:

     - 田中太郎 (Tanaka Taro) — under 40, no 介護保険料, Tokyo
     - 鈴木花子 (Suzuki Hanako) — over 40, includes 介護保険料, Tokyo
     - 佐藤次郎 (Sato Jiro) — under 40, Osaka

   Run a monthly payroll through compute → posting → audit-doc + bonus
   accrual for the summer cycle + year-end Gensen aggregation.

   Per ADR-084 §10.3 the acceptance criteria are:
     - A JP KK with N employees posts a payroll via the MockJpCompute
       (or a freee CSV) through `run-payroll!`.
     - Postings sum to zero per (ledger × commodity).
     - 賞与引当金 helpers produce balanced accrual tx-data.
     - Year-end Gensen aggregator returns one statement per
       (person, employer, tax-year) with the right 支払金額 +
       源泉徴収税額 totals."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.hr.compensation :as comp]
            [kontor.hr.core :as hr]
            [kontor.hr.employment :as employment]
            [kontor.hr.pay-period :as pp]
            [kontor.hr.payroll :as payroll]
            [kontor.hr.person :as person]
            [kontor.l10n-jp.chart :as jp-chart]
            [kontor.payroll-jp.accrual :as jp-accrual]
            [kontor.payroll-jp.chart :as pjp-chart]
            [kontor.payroll-jp.emit :as jp-emit]
            [kontor.payroll-jp.gensen :as jp-gensen]
            [kontor.payroll-jp.posting-builder :as jp-pb]
            [kontor.payroll-provider :as ppro])
  (:import [java.math BigDecimal]))

(defn- bootstrap []
  (let [conn (core/create-test-db)]
    (hr/install! conn)
    (jp-chart/install! conn)
    (pjp-chart/install! conn)
    (d/transact conn
                [{:db/id "ent-acme-jp"
                  :kontor.entity/code "ACME-JP"
                  :kontor.entity/name "Acme株式会社"
                  :kontor.entity/kind :operating}
                 {:db/id "journal-pay"
                  :kontor.journal/code "PAY-JP"
                  :kontor.journal/name "Payroll (JP)"
                  :kontor.journal/type :general}
                 {:db/id "period-2026-05"
                  :period/name "2026-05"
                  :period/start #inst "2026-05-01"
                  :period/end #inst "2026-06-01"}])
    conn))

(defn- get-account-eid [db code]
  (d/q '[:find ?e . :in $ ?c :where [?e :kontor.account/code ?c]] db code))

;; ============================================================================
;; Mock compute provider
;; ============================================================================

(defrecord MockJpCompute [opts]
  ppro/PayrollComputeProvider
  (provider-id [_] :mock-jp)
  (compute-payroll [_ {:keys [employment-eids]}]
    (mapv (fn [eid]
            (let [{:keys [profile]} (get (:per-emp opts) eid)]
              (case profile
                ;; Profile :senior — over 40, includes 介護保険料
                :senior
                {:employment eid
                 :gross 500000M
                 :net 393242M
                 :components [{:kind :base-wage                     :amount 470000M  :employer-side? false}
                              {:kind :commuting-allowance           :amount 18000M   :employer-side? false}
                              {:kind :housing-allowance             :amount 12000M   :employer-side? false}
                              {:kind :employee-health-insurance     :amount -27500M  :employer-side? false}
                              {:kind :employee-pension              :amount -45750M  :employer-side? false}
                              {:kind :employee-long-term-care       :amount -4510M   :employer-side? false}
                              {:kind :employee-employment-insurance :amount -2998M   :employer-side? false}
                              {:kind :income-tax-withheld           :amount -22000M  :employer-side? false}
                              {:kind :resident-tax-withheld         :amount -4000M   :employer-side? false}
                              {:kind :employer-health-insurance     :amount 27500M   :employer-side? true}
                              {:kind :employer-pension              :amount 45750M   :employer-side? true}
                              {:kind :employer-long-term-care       :amount 4510M    :employer-side? true}
                              {:kind :employer-employment-insurance :amount 4500M    :employer-side? true}]
                 :jurisdiction-specific-codes
                 {:engine :mock-jp :prefecture "東京都"}}

                ;; Profile :junior — under 40, no 介護保険料
                :junior
                {:employment eid
                 :gross 340000M
                 :net 264960M
                 :components [{:kind :base-wage                     :amount 300000M  :employer-side? false}
                              {:kind :commuting-allowance           :amount 15000M   :employer-side? false}
                              {:kind :overtime                      :amount 25000M   :employer-side? false}
                              {:kind :employee-health-insurance     :amount -16500M  :employer-side? false}
                              {:kind :employee-pension              :amount -30500M  :employer-side? false}
                              {:kind :employee-employment-insurance :amount -2040M   :employer-side? false}
                              {:kind :income-tax-withheld           :amount -8000M   :employer-side? false}
                              {:kind :resident-tax-withheld         :amount -18000M  :employer-side? false}
                              {:kind :employer-health-insurance     :amount 16500M   :employer-side? true}
                              {:kind :employer-pension              :amount 30500M   :employer-side? true}
                              {:kind :employer-employment-insurance :amount 3060M    :employer-side? true}]
                 :jurisdiction-specific-codes
                 {:engine :mock-jp :prefecture "東京都"}}

                ;; Profile :osaka — under 40, Osaka prefecture (different 健保 rate)
                :osaka
                {:employment eid
                 :gross 380000M
                 :net 296464M
                 :components [{:kind :base-wage                     :amount 350000M  :employer-side? false}
                              {:kind :commuting-allowance           :amount 20000M   :employer-side? false}
                              {:kind :overtime                      :amount 10000M   :employer-side? false}
                              {:kind :employee-health-insurance     :amount -19300M  :employer-side? false}
                              {:kind :employee-pension              :amount -34770M  :employer-side? false}
                              {:kind :employee-employment-insurance :amount -2280M   :employer-side? false}
                              {:kind :income-tax-withheld           :amount -10500M  :employer-side? false}
                              {:kind :resident-tax-withheld         :amount -16686M  :employer-side? false}
                              {:kind :employer-health-insurance     :amount 19300M   :employer-side? true}
                              {:kind :employer-pension              :amount 34770M   :employer-side? true}
                              {:kind :employer-employment-insurance :amount 3420M    :employer-side? true}]
                 :jurisdiction-specific-codes
                 {:engine :mock-jp :prefecture "大阪府"}})))
          employment-eids)))

;; ============================================================================
;; The monthly end-to-end
;; ============================================================================

(deftest jp-payroll-monthly-end-to-end
  (let [conn (bootstrap)
        db (d/db conn)
        jpy (d/q '[:find ?e . :where [?e :kontor.commodity/symbol "JPY"]] db)
        ent (d/q '[:find ?e . :where [?e :kontor.entity/code "ACME-JP"]] db)
        journal (d/q '[:find ?e . :where [?e :kontor.journal/code "PAY-JP"]] db)
        period (d/q '[:find ?e . :where [?e :period/name "2026-05"]] db)
        ;; Persons + employments
        _ (person/create-person!
           conn {:external-id "P-tanaka"
                 :given-name "太郎" :family-name "田中"})
        _ (person/create-person!
           conn {:external-id "P-suzuki"
                 :given-name "花子" :family-name "鈴木"})
        _ (person/create-person!
           conn {:external-id "P-sato"
                 :given-name "次郎" :family-name "佐藤"})
        db (d/db conn)
        tanaka (hr/person-by-external-id db "P-tanaka")
        suzuki (hr/person-by-external-id db "P-suzuki")
        sato   (hr/person-by-external-id db "P-sato")
        _ (employment/hire! conn {:code "EMP-tanaka"
                                  :person tanaka :entity ent
                                  :start-date #inst "2025-04-01"
                                  :job-title "ソフトウェアエンジニア"})
        _ (employment/hire! conn {:code "EMP-suzuki"
                                  :person suzuki :entity ent
                                  :start-date #inst "2020-04-01"
                                  :job-title "マネージャー"})
        _ (employment/hire! conn {:code "EMP-sato"
                                  :person sato :entity ent
                                  :start-date #inst "2024-04-01"
                                  :job-title "デザイナー"})
        db (d/db conn)
        tanaka-emp (hr/employment-by-code db "EMP-tanaka")
        suzuki-emp (hr/employment-by-code db "EMP-suzuki")
        sato-emp   (hr/employment-by-code db "EMP-sato")
        _ (comp/set-compensation!
           conn {:employment tanaka-emp
                 :effective-from #inst "2025-04-01"
                 :commodity jpy
                 :components [{:kind :base-wage :amount 3600000M :period :annual}]})
        _ (comp/set-compensation!
           conn {:employment suzuki-emp
                 :effective-from #inst "2020-04-01"
                 :commodity jpy
                 :components [{:kind :base-wage :amount 5640000M :period :annual}]})
        _ (comp/set-compensation!
           conn {:employment sato-emp
                 :effective-from #inst "2024-04-01"
                 :commodity jpy
                 :components [{:kind :base-wage :amount 4200000M :period :annual}]})
        _ (pp/create-pay-period!
           conn {:code "ACME-JP-2026-05" :entity ent
                 :start-date #inst "2026-05-01"
                 :end-date #inst "2026-05-31"
                 :frequency :monthly
                 :fiscal-period period})
        pp-eid (hr/pay-period-by-code (d/db conn) "ACME-JP-2026-05")
        ;; Account refs by tag → eid for the posting builder
        db (d/db conn)
        accounts {:jp-payroll-wages                  (get-account-eid db "610000")
                  :jp-payroll-bonus                  (get-account-eid db "611000")
                  :jp-payroll-er-statutory-benefits  (get-account-eid db "612000")
                  :jp-payroll-health-insurance       (get-account-eid db "216100")
                  :jp-payroll-pension                (get-account-eid db "216200")
                  :jp-payroll-employment-insurance   (get-account-eid db "216300")
                  :jp-payroll-long-term-care         (get-account-eid db "216400")
                  :jp-payroll-income-tax             (get-account-eid db "216500")
                  :jp-payroll-resident-tax           (get-account-eid db "216600")
                  :jp-payroll-zaikei                 (get-account-eid db "216700")
                  :jp-payroll-union-dues             (get-account-eid db "216800")
                  :jp-payroll-other-deduction        (get-account-eid db "216900")
                  :jp-payroll-net-wages              (get-account-eid db "214100")}
        compute-provider (->MockJpCompute
                          {:per-emp {tanaka-emp {:profile :junior}
                                     suzuki-emp {:profile :senior}
                                     sato-emp   {:profile :osaka}}})
        posting-builder (jp-pb/->JpPayrollPostingBuilder {:commodity jpy})
        emit-provider (jp-emit/->JpPayrollEmitProvider {:language :ja})
        report (payroll/run-payroll!
                conn {:pay-period pp-eid
                      :entity ent
                      :employments [tanaka-emp suzuki-emp sato-emp]
                      :compute-provider compute-provider
                      :posting-builder posting-builder
                      :emit-provider emit-provider
                      :accounts accounts
                      :run-code "ACME-JP-2026-05-001"
                      :tx-code "TX-ACME-JP-2026-05"
                      :journal journal
                      :commodity jpy})
        db (:db-after report)
        run-eid (d/q '[:find ?r . :in $ ?c
                       :where [?r :payroll-run/code ?c]]
                     db "ACME-JP-2026-05-001")
        run (d/pull db '[* {:payroll-run/payroll-transaction
                            [:kontor.transaction/external-id
                             {:kontor.posting/_transaction
                              [:kontor.posting/amount
                               {:kontor.posting/account [:kontor.account/code]}]}]}]
                    run-eid)]
    (testing "payroll-run row created"
      (is (some? run-eid))
      (is (= :computed (:payroll-run/state run)))
      (is (= :mock-jp (:payroll-run/provider-id run))))
    (testing "Control totals reflect all three employees"
      ;; Tanaka 340000 + Suzuki 500000 + Sato 380000 = 1_220_000
      (is (= 1220000M (:payroll-run/control-total-gross run))))
    (testing "Posting legs sum to zero per (ledger × commodity)"
      (let [postings (-> run :payroll-run/payroll-transaction
                         :kontor.posting/_transaction)
            sum (reduce (fn [a {:kontor.posting/keys [amount]}]
                          (.add ^BigDecimal a ^BigDecimal amount))
                        0M postings)]
        (is (zero? (.compareTo ^BigDecimal sum 0M)))))
    (testing "Long-term-care (介護保険料) posts ONLY for Suzuki (≥40)"
      (let [postings (-> run :payroll-run/payroll-transaction
                         :kontor.posting/_transaction)
            by-code (group-by (comp :kontor.account/code :kontor.posting/account) postings)
            kaigo-total (reduce (fn [a {:kontor.posting/keys [amount]}]
                                  (.add ^BigDecimal a ^BigDecimal amount))
                                0M (get by-code "216400"))]
        ;; -4510 (employee) + -4510 (employer payable) = -9020
        (is (= -9020M kaigo-total))))
    (testing "Income tax + resident tax route to DIFFERENT liability buckets"
      (let [postings (-> run :payroll-run/payroll-transaction
                         :kontor.posting/_transaction)
            by-code (group-by (comp :kontor.account/code :kontor.posting/account) postings)
            itx-total (reduce (fn [a {:kontor.posting/keys [amount]}]
                                (.add ^BigDecimal a ^BigDecimal amount))
                              0M (get by-code "216500"))
            rt-total (reduce (fn [a {:kontor.posting/keys [amount]}]
                               (.add ^BigDecimal a ^BigDecimal amount))
                             0M (get by-code "216600"))]
        ;; ITX: -8000 + -22000 + -10500 = -40500
        (is (= -40500M itx-total))
        ;; Resident tax: -18000 + -4000 + -16686 = -38686
        (is (= -38686M rt-total))))
    (testing "Emit produced :audit-doc/category :payroll-filing + :audit-doc/language :ja"
      (let [doc-eids (d/q '[:find [?e ...]
                            :where
                            [?e :audit-doc/category :payroll-filing]
                            [?e :audit-doc/language :ja]]
                          db)]
        (is (>= (count doc-eids) 1))))))

;; ============================================================================
;; Bonus accrual integration
;; ============================================================================

(deftest jp-bonus-accrual-balances-and-transacts
  (let [conn (bootstrap)
        db (d/db conn)
        jpy (d/q '[:find ?e . :where [?e :kontor.commodity/symbol "JPY"]] db)
        journal (d/q '[:find ?e . :where [?e :kontor.journal/code "PAY-JP"]] db)
        bonus-exp-acct (get-account-eid db "614000")
        bonus-liab-acct (get-account-eid db "217000")
        accrual-amount (jp-accrual/bonus-accrual-amount
                        {:annual-bonus-target 1800000M
                         :periods-in-cycle 6})
        tx (jp-accrual/bonus-accrual-tx-data
            {:bonus-accrual-expense-account bonus-exp-acct
             :bonus-accrual-liability-account bonus-liab-acct
             :amount accrual-amount
             :commodity jpy
             :journal journal
             :effective-date #inst "2026-05-31"
             :tx-code "BONUS-ACC-2026-05"})
        ;; Use validation/transact-with-validation per ADR-068
        report ((requiring-resolve 'kontor.validation/transact-with-validation)
                conn tx)
        db (:db-after report)]
    (testing "Bonus expense booked"
      (let [postings (d/q '[:find ?amount
                            :in $ ?a
                            :where
                            [?p :kontor.posting/account ?a]
                            [?p :kontor.posting/amount ?amount]]
                          db bonus-exp-acct)]
        (is (= 300000M (ffirst postings)))))
    (testing "Bonus liability booked"
      (let [postings (d/q '[:find ?amount
                            :in $ ?a
                            :where
                            [?p :kontor.posting/account ?a]
                            [?p :kontor.posting/amount ?amount]]
                          db bonus-liab-acct)]
        (is (= -300000M (ffirst postings)))))))

;; ============================================================================
;; Year-end Gensen aggregation
;; ============================================================================

(deftest jp-gensen-aggregates-12-monthly-runs-plus-2-bonuses
  "Simulate a full year of payroll for one employee + 2 bonuses; the
   Gensen aggregator must reduce the 14 facts into one statement."
  (let [conn (bootstrap)
        ;; Per-fact components (junior profile from MockJpCompute).
        monthly-fact (fn [emp]
                       {:employment emp
                        :gross 340000M
                        :net 264960M
                        :components [{:kind :base-wage                     :amount 300000M  :employer-side? false}
                                     {:kind :commuting-allowance           :amount 15000M   :employer-side? false}
                                     {:kind :overtime                      :amount 25000M   :employer-side? false}
                                     {:kind :employee-health-insurance     :amount -16500M  :employer-side? false}
                                     {:kind :employee-pension              :amount -30500M  :employer-side? false}
                                     {:kind :employee-employment-insurance :amount -2040M   :employer-side? false}
                                     {:kind :income-tax-withheld           :amount -8000M   :employer-side? false}
                                     {:kind :resident-tax-withheld         :amount -18000M  :employer-side? false}]})
        bonus-fact (fn [emp]
                     {:employment emp
                      :gross 600000M
                      :net 510000M
                      :components [{:kind :bonus              :amount 600000M  :employer-side? false}
                                   {:kind :income-tax-withheld :amount -60000M :employer-side? false}
                                   {:kind :employee-pension   :amount -30000M  :employer-side? false}]})
        emp :emp/tanaka
        facts (vec (concat (repeat 12 (monthly-fact emp))
                           [(bonus-fact emp) (bonus-fact emp)]))
        person-rec {:given-name "太郎"
                    :family-name "田中"
                    :address "東京都新宿区..."
                    :birth-date #inst "1990-06-12"
                    :my-number-present? true}
        employer-rec {:name "Acme株式会社"
                      :corporate-number "8700110005901"
                      :address "東京都港区..."
                      :representative "代表取締役 山田一郎"}
        statements (jp-gensen/build-gensen-submission
                    {:facts facts
                     :tax-year 2026
                     :employer employer-rec
                     :employment->person+employer
                     {emp {:person person-rec :employer employer-rec}}})]
    (testing "One statement (single person × employer × year)"
      (is (= 1 (count statements))))
    (testing "支払金額 reflects 12 monthly grosses + 2 bonuses"
      ;; 12 × 340000 + 2 × 600000 = 4_080_000 + 1_200_000 = 5_280_000
      (is (= 5280000M (-> statements first :gensen/payment-amount))))
    (testing "源泉徴収税額 reflects 12 monthly ITX + 2 bonus ITX"
      ;; 12 × 8000 + 2 × 60000 = 96_000 + 120_000 = 216_000
      (is (= 216000M (-> statements first :gensen/withholding-amount))))
    (testing "社会保険料等 reflects 12 monthly SI + 2 bonus pension"
      ;; 12 × (16500 + 30500 + 2040) + 2 × 30000 = 588_480 + 60_000 = 648_480
      (is (= 648480M (-> statements first :gensen/social-insurance-paid))))
    (testing "Resident tax does NOT appear on Gensen"
      ;; If resident tax leaked, withholding-amount would be > 216_000.
      (is (= 216000M (-> statements first :gensen/withholding-amount))))
    (testing "Gensen audit-doc builder produces :payroll-filing :ja"
      (let [docs (jp-emit/build-gensen-submission-audit-docs-tx-data
                  {:statements statements})]
        (is (= 1 (count docs)))
        (is (= :payroll-filing (:audit-doc/category (first docs))))
        (is (= :ja (:audit-doc/language (first docs))))))
    ;; Suppress reflection-warning on conn:
    (is (some? conn))))

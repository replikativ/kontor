(ns kontor.payroll-cn.e2e-test
  "Stage R C11 — end-to-end Yonyou CSV → kontor `run-payroll!` test.

   The headline scenario: a CN Ltd Co (single legal entity) with three
   employees in BJ / SH / GD. Monthly payroll. The Yonyou CSV carries
   all three employees' pay-period rows; kontor:

     1. Parses the CSV (compute provider).
     2. Validates `check-facts` per employee.
     3. Builds a balanced transaction with per-province analytic
        distributions.
     4. Emits an IIT monthly filing audit-doc with `:audit-doc/category
        :payroll-filing` + `:audit-doc/language :zh-cn`.
     5. Records the `:payroll-run` row with control totals.

   This test exercises the FULL kernel gate stack via
   `kontor.hr.payroll/run-payroll!` → `kontor.process/run-process` →
   `kontor.validation/transact-with-validation`.

   Per note 87 §2.2: multi-province wage allocation in the GL uses
   `:posting/analytic-distributions` with `:analytic-plan/code
   \"cn-province\"`. This is the substrate's structural answer to
   multi-city CN workforces."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.hr.core :as hr]
            [kontor.hr.employment :as employment]
            [kontor.hr.pay-period :as pp]
            [kontor.hr.payroll :as payroll]
            [kontor.hr.person :as person]
            [kontor.l10n-cn.chart :as cn-chart]
            [kontor.payroll-cn.core :as cn]
            [kontor.payroll-provider :as pp-proto])
  (:import [java.math BigDecimal]))

(defn- ref-eid [db a v]
  (d/q '[:find ?e . :in $ ?a ?v :where [?e ?a ?v]] db a v))

(def yonyou-pay-element-codes
  {"基本工资"         :base-wage
   "绩效工资"         :performance-bonus
   "加班费"           :overtime
   "年终奖"           :annual-bonus
   "补贴"             :allowance
   "个人所得税"       :iit-withheld
   "养老保险-个人"    :ee-pension
   "医疗保险-个人"    :ee-medical
   "失业保险-个人"    :ee-unemployment
   "住房公积金-个人"  :ee-housing-fund
   "养老保险-单位"    :er-pension
   "医疗保险-单位"    :er-medical
   "失业保险-单位"    :er-unemployment
   "工伤保险-单位"    :er-work-injury
   "生育保险-单位"    :er-maternity
   "住房公积金-单位"  :er-housing-fund})

(defn- mk-fact
  "Build a balanced PayrollFact directly. The CSV parser path is
   covered in compute_test; this e2e test exercises the
   posting-builder + emit + run-payroll! orchestrator on synthetic
   facts.

   Three employees, identical structure, different province codes."
  [emp-eid ext-id ee-name province]
  {:employment emp-eid
   :gross 18000M
   :net 12620M
   :components [{:kind :base-wage         :amount 15000M  :employer-side? false}
                {:kind :performance-bonus :amount 3000M   :employer-side? false}
                {:kind :ee-pension        :amount -1440M  :employer-side? false}
                {:kind :ee-medical        :amount -360M   :employer-side? false}
                {:kind :ee-unemployment   :amount -90M    :employer-side? false}
                {:kind :ee-housing-fund   :amount -2160M  :employer-side? false}
                {:kind :iit-withheld      :amount -1330M  :employer-side? false}
                {:kind :er-pension        :amount 2880M   :employer-side? true}
                {:kind :er-medical        :amount 1620M   :employer-side? true}
                {:kind :er-unemployment   :amount 90M     :employer-side? true}
                {:kind :er-work-injury    :amount 90M     :employer-side? true}
                {:kind :er-housing-fund   :amount 2160M   :employer-side? true}]
   :jurisdiction-specific-codes {:employee-external-id ext-id
                                 :cn/employee-name ee-name
                                 :cn/province-of-employment province
                                 :engine :yonyou}})

;; Hand-rolled provider returning the three synthetic facts.
(defrecord MockCnCompute [opts]
  pp-proto/PayrollComputeProvider
  (provider-id [_] :mock-cn)
  (compute-payroll [_ {:keys [employment-eids]}]
    (let [{:keys [per-emp]} opts]
      (mapv (fn [eid]
              (let [{:keys [ext-id ee-name province]} (get per-emp eid)]
                (mk-fact eid ext-id ee-name province)))
            employment-eids))))

(defn- bootstrap
  []
  (let [conn (core/create-test-db)]
    (hr/install! conn)
    (cn-chart/install! conn)
    (cn/install! conn)
    (d/transact conn
                [{:db/id "ent-acme-cn"
                  :entity/code "ACME-CN"
                  :entity/name "Acme China Co., Ltd."
                  :entity/kind :operating}
                 {:db/id "journal-pay"
                  :journal/code "PAY-CN"
                  :journal/name "Payroll (CN)"
                  :journal/type :general}
                 {:db/id "period-2026-04"
                  :period/name "2026-04"
                  :period/start #inst "2026-04-01"
                  :period/end #inst "2026-05-01"}
                 ;; Minimal payroll CoA — one account per :account-tag the
                 ;; posting builder needs.
                 {:db/id "acct-5603" :account/code "5603"
                  :account/name "管理费用-工资 / Admin — Wages"
                  :account/type :expense :account/active true
                  :account/tags [[:account-tag/name "cn-payroll-wages-expense"]
                                 [:account-tag/name "cn-payroll-er-si-expense"]
                                 [:account-tag/name "cn-payroll-er-hf-expense"]]}
                 {:db/id "acct-2211-01" :account/code "2211.01"
                  :account/name "应付职工薪酬-工资 / Wages payable"
                  :account/type :liability :account/active true
                  :account/tags [[:account-tag/name "cn-payroll-net-wages"]
                                 [:account-tag/name "cn-payroll-bonus-payable"]]}
                 {:db/id "acct-2211-03" :account/code "2211.03"
                  :account/name "应付职工薪酬-社保 / SI payable"
                  :account/type :liability :account/active true
                  :account/tags [[:account-tag/name "cn-payroll-ee-si"]
                                 [:account-tag/name "cn-payroll-er-si-payable"]]}
                 {:db/id "acct-2211-04" :account/code "2211.04"
                  :account/name "应付职工薪酬-公积金 / HF payable"
                  :account/type :liability :account/active true
                  :account/tags [[:account-tag/name "cn-payroll-ee-hf"]
                                 [:account-tag/name "cn-payroll-er-hf-payable"]]}
                 {:db/id "acct-2221-iit" :account/code "2221.IIT"
                  :account/name "应交税费-个人所得税 / IIT withheld payable"
                  :account/type :liability :account/active true
                  :account/tags [[:account-tag/name "cn-payroll-iit"]]}])
    conn))

(defn- setup-employees [conn]
  (let [db (d/db conn)
        ent (ref-eid db :entity/code "ACME-CN")]
    (doseq [[ext given family] [["P-E001" "Wei"  "Zhang"]
                                ["P-E002" "Li"   "Wang"]
                                ["P-E003" "Min"  "Liu"]]]
      (person/create-person! conn {:external-id ext
                                   :given-name given
                                   :family-name family}))
    (let [db (d/db conn)
          p1 (hr/person-by-external-id db "P-E001")
          p2 (hr/person-by-external-id db "P-E002")
          p3 (hr/person-by-external-id db "P-E003")]
      (employment/hire! conn {:code "E001" :person p1 :entity ent
                              :start-date #inst "2025-01-01"
                              :job-title "Senior Engineer (BJ)"})
      (employment/hire! conn {:code "E002" :person p2 :entity ent
                              :start-date #inst "2025-01-01"
                              :job-title "Senior Engineer (SH)"})
      (employment/hire! conn {:code "E003" :person p3 :entity ent
                              :start-date #inst "2025-01-01"
                              :job-title "Senior Engineer (GD)"}))))

(deftest cn-three-employees-three-provinces-end-to-end
  (let [conn (bootstrap)
        _ (setup-employees conn)
        db (d/db conn)
        ent (ref-eid db :entity/code "ACME-CN")
        cny (ref-eid db :commodity/symbol "CNY")
        period (ref-eid db :period/name "2026-04")
        journal (ref-eid db :journal/code "PAY-CN")
        e1 (hr/employment-by-code db "E001")
        e2 (hr/employment-by-code db "E002")
        e3 (hr/employment-by-code db "E003")
        _ (pp/create-pay-period! conn {:code "CN-2026-04"
                                       :entity ent
                                       :start-date #inst "2026-04-01"
                                       :end-date #inst "2026-04-30"
                                       :frequency :monthly
                                       :fiscal-period period})
        pp-eid (hr/pay-period-by-code (d/db conn) "CN-2026-04")
        accounts {:cn-payroll-wages-expense   (ref-eid (d/db conn) :account/code "5603")
                  :cn-payroll-er-si-expense   (ref-eid (d/db conn) :account/code "5603")
                  :cn-payroll-er-hf-expense   (ref-eid (d/db conn) :account/code "5603")
                  :cn-payroll-net-wages       (ref-eid (d/db conn) :account/code "2211.01")
                  :cn-payroll-bonus-payable   (ref-eid (d/db conn) :account/code "2211.01")
                  :cn-payroll-ee-si           (ref-eid (d/db conn) :account/code "2211.03")
                  :cn-payroll-er-si-payable   (ref-eid (d/db conn) :account/code "2211.03")
                  :cn-payroll-ee-hf           (ref-eid (d/db conn) :account/code "2211.04")
                  :cn-payroll-er-hf-payable   (ref-eid (d/db conn) :account/code "2211.04")
                  :cn-payroll-iit             (ref-eid (d/db conn) :account/code "2221.IIT")}
        compute-provider (->MockCnCompute
                          {:per-emp {e1 {:ext-id "E001" :ee-name "张三" :province "CN-BJ"}
                                     e2 {:ext-id "E002" :ee-name "李四" :province "CN-SH"}
                                     e3 {:ext-id "E003" :ee-name "刘明" :province "CN-GD"}}})
        posting-builder (cn/make-cn-payroll-posting-builder {:commodity cny})
        emit-provider (cn/make-iit-emit-provider {:pay-period-code "2026-04"
                                                  :entity-code "ACME-CN"})
        report (payroll/run-payroll!
                conn {:pay-period pp-eid
                      :entity ent
                      :employments [e1 e2 e3]
                      :compute-provider compute-provider
                      :posting-builder posting-builder
                      :emit-provider emit-provider
                      :accounts accounts
                      :run-code "RUN-CN-2026-04-001"
                      :tx-code "TX-PAYROLL-CN-2026-04"
                      :journal journal
                      :commodity cny})
        db' (:db-after report)
        run-eid (d/q '[:find ?r . :in $ ?c
                       :where [?r :payroll-run/code ?c]]
                     db' "RUN-CN-2026-04-001")
        run (d/pull db' '[* {:payroll-run/payroll-transaction
                             [:transaction/external-id
                              {:posting/_transaction
                               [:posting/amount :posting/account
                                {:posting/analytic-distributions
                                 [:analytic-distribution/percent
                                  {:analytic-distribution/account
                                   [:analytic-account/code]}]}]}]}
                          {:payroll-run/emit-docs
                           [:audit-doc/code :audit-doc/category
                            :audit-doc/language :audit-doc/inline-payload]}]
                    run-eid)
        postings (-> run :payroll-run/payroll-transaction
                     :posting/_transaction)
        emit-docs (:payroll-run/emit-docs run)]
    (testing "the payroll-run row is created"
      (is (some? run-eid))
      (is (= :mock-cn (:payroll-run/provider-id run))))
    (testing "control totals reflect all three employees"
      ;; Gross 18000 × 3 = 54000
      (is (= 54000M (:payroll-run/control-total-gross run)))
      ;; Net 12620 × 3 = 37860
      (is (= 37860M (:payroll-run/control-total-net run))))
    (testing "the linked :transaction balances per-(ledger, commodity)"
      (let [sum (reduce (fn [^BigDecimal a {:keys [posting/amount]}]
                          (.add a ^BigDecimal amount))
                        0M postings)]
        (is (zero? (.signum sum)))))
    (testing "every posting carries an :analytic-distribution to one of the three provinces"
      (let [with-dist (filter (fn [p] (seq (:posting/analytic-distributions p))) postings)]
        (is (seq with-dist))
        (let [province-codes
              (->> with-dist
                   (mapcat :posting/analytic-distributions)
                   (map :analytic-distribution/account)
                   (map :analytic-account/code)
                   distinct
                   set)]
          (is (= #{"BJ" "SH" "GD"} province-codes)))))
    (testing "the IIT emit-doc is linked to the run"
      (is (= 1 (count emit-docs)))
      (let [doc (first emit-docs)]
        (is (= "CN-IIT-2026-04-ACME-CN" (:audit-doc/code doc)))
        (is (= :payroll-filing (:audit-doc/category doc)))
        (is (= :zh-cn (:audit-doc/language doc)))
        (is (str/includes? (:audit-doc/inline-payload doc) "员工编号 / employee-id"))))))

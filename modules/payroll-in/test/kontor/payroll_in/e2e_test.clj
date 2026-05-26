(ns kontor.payroll-in.e2e-test
  "End-to-end IN payroll flow — one Pvt Ltd, 3 employees across
   Maharashtra / Karnataka / Tamil Nadu, monthly run via Keka CSV
   adapter through `run-payroll!`.

   Per note 79 §5.3 C9 acceptance criterion:
     - An IN Pvt Ltd with N employees posts a payroll via the
       Keka CSV adapter through `run-payroll!`.
     - Multi-state PT routes per state via :analytic-distribution
       on the :in-state plan.
     - Quarterly TDS helper returns correct Sec-192 total.
     - Monthly ECR / ESIC helpers return correct payable totals."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.hr.core :as hr]
            [kontor.hr.employment :as employment]
            [kontor.hr.pay-period :as pay-period]
            [kontor.hr.payroll :as payroll]
            [kontor.hr.person :as person]
            [kontor.l10n-in.chart :as in-chart]
            [kontor.payroll-in.core :as in-payroll]
            [kontor.payroll-in.emit :as emit]
            [kontor.payroll-in.pf :as pf]
            [kontor.payroll-in.tds :as tds]
            [kontor.payroll-provider :as pp])
  (:import [java.math BigDecimal]))

;; ============================================================================
;; Bootstrap — install kernel + hr + in chart + in payroll
;; ============================================================================

(defn- ref-eid [db a v]
  (d/q '[:find ?e . :in $ ?a ?v :where [?e ?a ?v]] db a v))

(defn- bootstrap []
  (let [conn (core/create-test-db)]
    (hr/install! conn)
    (in-chart/install! conn)
    (in-payroll/install! conn)
    ;; Test fixtures
    (d/transact conn
                [{:db/id "ent-acme-in"
                  :kontor.entity/code "ACME-IN"
                  :kontor.entity/name "Acme India Pvt Ltd"
                  :kontor.entity/kind :operating}
                 {:db/id "journal-pay"
                  :journal/code "PAY-IN"
                  :journal/name "Payroll (IN)"
                  :journal/type :general}
                 {:db/id "ledger-ifrs-in"
                  :ledger/code "ind-as"
                  :ledger/name "Ind AS"
                  :ledger/framework :ifrs
                  :ledger/active true}
                 {:db/id "period-2026-05"
                  :period/name "2026-05"
                  :period/start #inst "2026-05-01"
                  :period/end #inst "2026-06-01"}])
    conn))

(defn- setup-employees [conn]
  (let [db (d/db conn)
        acme (ref-eid db :kontor.entity/code "ACME-IN")]
    (doseq [[ext given family] [["P-E001" "Ravi" "Sharma"]    ; MH
                                ["P-E002" "Anita" "Reddy"]    ; KA
                                ["P-E003" "Kiran" "Kumar"]]]  ; TN
      (person/create-person! conn {:external-id ext
                                   :given-name given
                                   :family-name family}))
    (let [db (d/db conn)
          ravi  (hr/person-by-external-id db "P-E001")
          anita (hr/person-by-external-id db "P-E002")
          kiran (hr/person-by-external-id db "P-E003")]
      (employment/hire! conn {:code "E001"
                              :person ravi :entity acme
                              :start-date #inst "2025-01-01"
                              :job-title "Senior Engineer (MH)"})
      (employment/hire! conn {:code "E002"
                              :person anita :entity acme
                              :start-date #inst "2025-01-01"
                              :job-title "Product Manager (KA)"})
      (employment/hire! conn {:code "E003"
                              :person kiran :entity acme
                              :start-date #inst "2025-01-01"
                              :job-title "Engineer (TN)"}))))

(def pay-element-codes
  {"BASIC"     :basic-salary
   "DA"        :dearness-allowance
   "HRA"       :house-rent-allowance
   "TDS"       :tds
   "PF-EE"     :pf-employee
   "PT"        :professional-tax
   "PF-ER"     {:kind :pf-employer :employer-side? true}})

(defn- consumer-accounts-map [db]
  ;; Look up by tag (the load-bearing identifier per ADR-083) so the
  ;; test isn't tied to specific account codes (which the consumer can
  ;; renumber).
  (let [by-tag (fn [tag]
                 (d/q '[:find ?a .
                        :in $ ?t
                        :where
                        [?a :kontor.account/tags ?at]
                        [?at :kontor.account-tag/name ?t]]
                      db (name tag)))]
    {:in-payroll-salaries-wages    (by-tag :in-payroll-salaries-wages)
     :in-payroll-bonus             (by-tag :in-payroll-bonus)
     :in-payroll-er-pf             (by-tag :in-payroll-er-pf)
     :in-payroll-er-esi            (by-tag :in-payroll-er-esi)
     :in-payroll-tds-payable       (by-tag :in-payroll-tds-payable)
     :in-payroll-pf-payable        (by-tag :in-payroll-pf-payable)
     :in-payroll-esi-payable       (by-tag :in-payroll-esi-payable)
     :in-payroll-pt-payable        (by-tag :in-payroll-pt-payable)
     :in-payroll-net-wages         (by-tag :in-payroll-net-wages)
     :in-payroll-bonus-accrual     (by-tag :in-payroll-bonus-accrual)
     :in-payroll-bonus-payable     (by-tag :in-payroll-bonus-payable)
     :in-payroll-leave-accrual     (by-tag :in-payroll-leave-accrual)
     :in-payroll-leave-liability   (by-tag :in-payroll-leave-liability)
     :in-payroll-gratuity-paid     (by-tag :in-payroll-gratuity-paid)
     :in-payroll-gratuity-accrual  (by-tag :in-payroll-gratuity-accrual)
     :in-payroll-gratuity-liability (by-tag :in-payroll-gratuity-liability)
     :in-payroll-other-deduction   (by-tag :in-payroll-other-deduction)
     :in-payroll-loan-recovery     (by-tag :in-payroll-loan-recovery)
     :in-payroll-garnishment       (by-tag :in-payroll-garnishment)}))

(deftest in-pvt-ltd-three-employees-three-states-end-to-end
  (let [conn (bootstrap)
        _ (setup-employees conn)
        db (d/db conn)
        acme    (ref-eid db :kontor.entity/code "ACME-IN")
        inr     (ref-eid db :kontor.commodity/symbol "INR")
        ledger  (ref-eid db :ledger/code "ind-as")
        journal (ref-eid db :journal/code "PAY-IN")
        period  (ref-eid db :period/name "2026-05")
        e001 (hr/employment-by-code db "E001")
        e002 (hr/employment-by-code db "E002")
        e003 (hr/employment-by-code db "E003")
        _ (pay-period/create-pay-period! conn {:code "ACME-IN-2026-05"
                                       :entity acme
                                       :start-date #inst "2026-05-01"
                                       :end-date #inst "2026-05-31"
                                       :frequency :monthly
                                       :fiscal-period period})
        pp-eid (hr/pay-period-by-code (d/db conn) "ACME-IN-2026-05")
        csv (slurp (io/resource "kontor/payroll_in/fixtures/keka_sample.csv"))
        compute-provider (in-payroll/make-keka-compute-provider
                          {:csv-source csv
                           :pay-element-codes pay-element-codes
                           :external-id->eid {"E001" e001 "E002" e002 "E003" e003}
                           :commodity-eid inr})
        posting-builder (in-payroll/make-in-payroll-posting-builder
                         {:commodity inr})
        emit-provider (in-payroll/make-in-payroll-emit-provider
                       {:language :en-in})
        accounts (consumer-accounts-map (d/db conn))
        report (payroll/run-payroll!
                conn {:pay-period pp-eid
                      :entity acme
                      :employments [e001 e002 e003]
                      :compute-provider compute-provider
                      :posting-builder posting-builder
                      :emit-provider emit-provider
                      :accounts accounts
                      :run-code "RUN-IN-2026-05-001"
                      :tx-code "TX-PAY-IN-2026-05"
                      :journal journal
                      :ledger ledger
                      :commodity inr})
        db' (:db-after report)
        run-eid (d/q '[:find ?r . :in $ ?c
                       :where [?r :payroll-run/code ?c]]
                     db' "RUN-IN-2026-05-001")
        run (d/pull db' '[* {:payroll-run/payroll-transaction
                             [:transaction/external-id
                              {:posting/_transaction
                               [:posting/amount
                                {:posting/account [:kontor.account/code
                                                   {:kontor.account/tags
                                                    [:kontor.account-tag/name]}]}
                                {:posting/analytic-distributions
                                 [:analytic-distribution/percent
                                  {:analytic-distribution/account
                                   [:analytic-account/code]}]}]}]}]
                    run-eid)
        postings (-> run :payroll-run/payroll-transaction
                     :posting/_transaction)]
    (testing "Payroll run row created with the expected provider-id"
      (is (some? run-eid))
      (is (= :keka (:payroll-run/provider-id run)))
      (is (= :computed (:payroll-run/state run))))
    (testing "Control totals match the 3-employee CSV"
      ;; E001 gross = 50000 + 5000 + 20000 = 75000
      ;; E002 gross = 40000 + 4000 + 16000 = 60000
      ;; E003 gross = 35000 + 3500 + 14000 = 52500
      ;; Total gross = 187500
      (is (= 187500M (:payroll-run/control-total-gross run)))
      ;; E001 net = 75000 - 4000 - 1800 - 200 = 69000
      ;; E002 net = 60000 - 2500 - 1800 - 200 = 55500
      ;; E003 net = 52500 - 1500 - 1800 - 250 = 48950
      ;; Total net = 173450
      (is (= 173450M (:payroll-run/control-total-net run))))
    (testing "Transaction balances per (ledger, commodity)"
      (let [sum (reduce (fn [^BigDecimal a {:keys [posting/amount]}]
                          (.add a ^BigDecimal amount))
                        0M postings)]
        (is (zero? (.signum sum)))))
    (testing "Per-state PT routing via analytic-distribution"
      (let [pt-postings (filter (fn [p]
                                  (some #(= "in-payroll-pt-payable"
                                            (:kontor.account-tag/name %))
                                        (:kontor.account/tags (:posting/account p))))
                                postings)]
        (is (= 3 (count pt-postings)) "One PT leg per employee")
        (let [state-codes
              (->> pt-postings
                   (mapcat :posting/analytic-distributions)
                   (map :analytic-distribution/account)
                   (map :analytic-account/code)
                   distinct
                   set)]
          (is (= #{"IN-MH" "IN-KA" "IN-TN"} state-codes)))))
    (testing "Emit provider produced :payroll-filing audit-docs"
      (let [docs (d/q '[:find [?e ...]
                        :where [?e :audit-doc/category :payroll-filing]]
                      db')]
        (is (>= (count docs) 1))))
    (testing "Emit provider stamped :en-in language"
      (let [docs (d/q '[:find [?e ...]
                        :where [?e :audit-doc/language :en-in]]
                      db')]
        (is (>= (count docs) 1))))
    (testing "TDS quarterly summary aggregates the run's TDS"
      ;; Q1 of FY 2026-27 spans Apr-Jun 2026; our payroll is in May 2026.
      ;; Total TDS withheld = 4000 + 2500 + 1500 = 8000
      (let [summary (tds/quarterly-tds-summary
                     conn {:fy 2026 :quarter 1})]
        (is (= 8000M (:amount (:tds summary))))
        (is (= 2026 (:fy summary)))
        (is (= 1 (:quarter summary)))))
    (testing "Monthly PF summary aggregates the run's PF payables"
      ;; Each employee: PF-EE 1800 + PF-ER 1800 = 3600. Three employees
      ;; = 10800.
      (let [summary (pf/monthly-pf-summary
                     conn {:year 2026 :month 5})]
        (is (= 10800M (:amount (:pf-total summary))))))))

;; ============================================================================
;; Multi-state PT warning
;; ============================================================================

(deftest multi-state-pt-emits-warning-audit-doc
  (testing "When facts span multiple PT-levying states, emit-provider
   produces a second audit-doc flagging the per-state filing need."
    (let [facts [{:employment 1
                  :gross 50000M :net 45000M
                  :components [{:kind :basic-salary :amount 50000M :employer-side? false}
                               {:kind :tds :amount -5000M :employer-side? false}]
                  :jurisdiction-specific-codes {:province-of-employment "IN-MH"}}
                 {:employment 2
                  :gross 40000M :net 36000M
                  :components [{:kind :basic-salary :amount 40000M :employer-side? false}
                               {:kind :tds :amount -4000M :employer-side? false}]
                  :jurisdiction-specific-codes {:province-of-employment "IN-KA"}}]
          provider (emit/->InPayrollEmitProvider {:language :en-in})
          docs (pp/emit-payroll-events
                provider facts {:pay-period-eid 99 :entity-eid 88})]
      (is (= 2 (count docs)) "One run summary + one multi-state warning")
      (let [titles (map :audit-doc/title docs)]
        (is (some #(re-find #"Multi-state PT detection" %) titles))))))

(deftest single-pt-state-no-warning
  (testing "Single PT state — no warning audit-doc emitted"
    (let [facts [{:employment 1
                  :gross 50000M :net 45000M
                  :components [{:kind :basic-salary :amount 50000M :employer-side? false}
                               {:kind :tds :amount -5000M :employer-side? false}]
                  :jurisdiction-specific-codes {:province-of-employment "IN-MH"}}]
          provider (emit/->InPayrollEmitProvider {:language :en-in})
          docs (pp/emit-payroll-events
                provider facts {:pay-period-eid 99 :entity-eid 88})]
      (is (= 1 (count docs))))))

(deftest non-pt-state-no-warning
  (testing "Delhi-only run (no PT levied) — single audit-doc, no
   multi-state warning"
    (let [facts [{:employment 1
                  :gross 50000M :net 45000M
                  :components [{:kind :basic-salary :amount 50000M :employer-side? false}
                               {:kind :tds :amount -5000M :employer-side? false}]
                  :jurisdiction-specific-codes {:province-of-employment "IN-DL"}}]
          provider (emit/->InPayrollEmitProvider {:language :en-in})
          docs (pp/emit-payroll-events
                provider facts {:pay-period-eid 99 :entity-eid 88})]
      (is (= 1 (count docs))))))

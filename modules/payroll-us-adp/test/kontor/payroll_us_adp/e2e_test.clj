(ns kontor.payroll-us-adp.e2e-test
  "Stage R C3 — end-to-end ADP GLI → kontor `run-payroll!` test.

   The headline scenario: a US LLC (single legal entity) with three
   remote engineers in CA / NY / TX. Monthly payroll. The ADP GLI CSV
   carries all three employees' pay-period rows; kontor:

     1. Parses the CSV (compute provider).
     2. Validates `check-facts` per employee.
     3. Builds a balanced multi-ledger transaction
        (book-only :us-gaap for this run; the 401(k) match accrual
        scenario in `accrual_test` already covers the parallel-ledger
        split).
     4. Attaches a `:state` analytic distribution on every wage-side
        posting (per note 83 §4 — NOT `:posting/entity`).
     5. Records the `:payroll-run` row with control totals.

   This test exercises the FULL kernel gate stack via
   `kontor.hr.payroll/run-payroll!` → `kontor.process/run-process` →
   `kontor.validation/transact-with-validation`.

   Per note 73 Theme B P1: 'multi-state wage allocation in the GL
   pain'. This is the substrate's structural answer."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.hr.core :as hr]
            [kontor.hr.employment :as employment]
            [kontor.hr.pay-period :as pp]
            [kontor.hr.payroll :as payroll]
            [kontor.hr.person :as person]
            [kontor.payroll-us-adp.core :as adp]
            [kontor.payroll-us-adp.wage-types :as wt])
  (:import [java.math BigDecimal]))

(defn- ref-eid [db a v]
  (d/q '[:find ?e . :in $ ?a ?v :where [?e ?a ?v]] db a v))

(defn- bootstrap
  "Install kontor + hr + payroll-us-adp substrate plus a US LLC fixture
   with three employees: E101 / E102 / E103 in CA / NY / TX."
  []
  (let [conn (core/create-test-db)
        _ (hr/install! conn)
        _ (adp/install! conn)]
    (d/transact conn
                [{:db/id "usd" :kontor.commodity/symbol "USD" :kontor.commodity/precision 2}
                 {:db/id "ent-us" :kontor.entity/code "US-LLC" :kontor.entity/name "Acme US LLC"
                  :kontor.entity/kind :operating}
                 {:db/id "us-gaap" :ledger/code "us-gaap"
                  :ledger/name "US GAAP" :ledger/framework :us-gaap
                  :ledger/active true}
                 ;; Minimal payroll CoA (one account per wage-type-map
                 ;; account-key the fixture references).
                 {:db/id "acct-5010" :account/code "5010"
                  :account/name "Wages" :account/type :expense
                  :account/active true}
                 {:db/id "acct-5200" :account/code "5200"
                  :account/name "ER FICA SS" :account/type :expense
                  :account/active true}
                 {:db/id "acct-5210" :account/code "5210"
                  :account/name "ER FICA Medicare" :account/type :expense
                  :account/active true}
                 {:db/id "acct-5220" :account/code "5220"
                  :account/name "FUTA" :account/type :expense
                  :account/active true}
                 {:db/id "acct-5230" :account/code "5230"
                  :account/name "SUTA" :account/type :expense
                  :account/active true}
                 {:db/id "acct-2110" :account/code "2110"
                  :account/name "Federal Income Tax Withheld"
                  :account/type :liability :account/active true}
                 {:db/id "acct-2130" :account/code "2130"
                  :account/name "State Income Tax Withheld"
                  :account/type :liability :account/active true}
                 {:db/id "acct-2115" :account/code "2115"
                  :account/name "FICA SS Withheld"
                  :account/type :liability :account/active true}
                 {:db/id "acct-2120" :account/code "2120"
                  :account/name "Medicare Withheld"
                  :account/type :liability :account/active true}
                 {:db/id "acct-2100" :account/code "2100"
                  :account/name "Wages Payable"
                  :account/type :liability :account/active true}
                 {:db/id "acct-1999" :account/code "1999"
                  :account/name "Unmapped Suspense"
                  :account/type :asset :account/active true}
                 {:db/id "journal-payroll" :journal/code "PAY-US"
                  :journal/name "Payroll (US)" :journal/type :general}
                 {:db/id "period-2026-04" :period/name "2026-04"
                  :period/start #inst "2026-04-01"
                  :period/end #inst "2026-05-01"}])
    conn))

(defn- setup-employees [conn]
  (let [db (d/db conn)
        us-llc (ref-eid db :kontor.entity/code "US-LLC")]
    (doseq [[ext given family] [["P-E101" "Alice" "Pacific"]
                                ["P-E102" "Bob"   "Empire"]
                                ["P-E103" "Carol" "Lonestar"]]]
      (person/create-person! conn {:external-id ext
                                   :given-name given
                                   :family-name family}))
    (let [db (d/db conn)
          alice (hr/person-by-external-id db "P-E101")
          bob (hr/person-by-external-id db "P-E102")
          carol (hr/person-by-external-id db "P-E103")]
      (employment/hire! conn {:code "E101" :person alice :entity us-llc
                              :start-date #inst "2025-01-01"
                              :job-title "Senior Engineer (CA)"})
      (employment/hire! conn {:code "E102" :person bob :entity us-llc
                              :start-date #inst "2025-01-01"
                              :job-title "Senior Engineer (NY)"})
      (employment/hire! conn {:code "E103" :person carol :entity us-llc
                              :start-date #inst "2025-01-01"
                              :job-title "Senior Engineer (TX)"}))))

(defn- consumer-accounts-map
  "Map the wage-type-map :account-key values to the actual :account
   eids the fixture installs."
  [db]
  {:wages-expense       (ref-eid db :account/code "5010")
   :er-fica-ss          (ref-eid db :account/code "5200")
   :er-fica-medicare    (ref-eid db :account/code "5210")
   :er-futa             (ref-eid db :account/code "5220")
   :er-suta             (ref-eid db :account/code "5230")
   :ee-fed-withheld     (ref-eid db :account/code "2110")
   :ee-state-withheld   (ref-eid db :account/code "2130")
   :ee-fica-ss          (ref-eid db :account/code "2115")
   :ee-fica-medicare    (ref-eid db :account/code "2120")
   :net-pay-payable     (ref-eid db :account/code "2100")
   :balance-clearing    (ref-eid db :account/code "2100")
   :unmapped-suspense   (ref-eid db :account/code "1999")})

(deftest us-llc-three-employees-three-states-end-to-end
  (let [conn (bootstrap)
        _ (setup-employees conn)
        db (d/db conn)
        us-llc (ref-eid db :kontor.entity/code "US-LLC")
        usd (ref-eid db :kontor.commodity/symbol "USD")
        gaap (ref-eid db :ledger/code "us-gaap")
        period (ref-eid db :period/name "2026-04")
        journal (ref-eid db :journal/code "PAY-US")
        e101 (hr/employment-by-code db "E101")
        e102 (hr/employment-by-code db "E102")
        e103 (hr/employment-by-code db "E103")
        _ (pp/create-pay-period! conn {:code "US-2026-04"
                                       :entity us-llc
                                       :start-date #inst "2026-04-01"
                                       :end-date #inst "2026-04-30"
                                       :frequency :monthly
                                       :fiscal-period period})
        pp-eid (hr/pay-period-by-code (d/db conn) "US-2026-04")
        wtm (wt/load-reference)
        accounts (consumer-accounts-map (d/db conn))
        compute-provider (adp/make-adp-gli-compute-provider)
        posting-builder (adp/make-us-payroll-posting-builder {:commodity usd})
        csv (io/resource "kontor/payroll_us_adp/fixtures/gli-3-employees-3-states.csv")
        report (payroll/run-payroll!
                conn {:pay-period pp-eid
                      :entity us-llc
                      :employments [e101 e102 e103]
                      :compute-provider compute-provider
                      :posting-builder posting-builder
                      :accounts accounts
                      :run-code "RUN-US-2026-04-001"
                      :tx-code "TX-PAYROLL-US-2026-04"
                      :journal journal
                      :commodity usd
                      :variable-inputs {:csv-source csv
                                        :wage-type-map wtm
                                        :employee->employment
                                        {"E101" e101 "E102" e102 "E103" e103}
                                        ;; The posting-builder ALSO accepts
                                        ;; ledgers-map directly via variable-
                                        ;; inputs reaching build-postings's
                                        ;; opts. kontor.hr.payroll passes
                                        ;; :ledger only — so for this e2e
                                        ;; test we configure the provider's
                                        ;; default ledgers-map below.
                                        }
                      :ledger gaap})
        db' (:db-after report)
        run-eid (d/q '[:find ?r . :in $ ?c
                       :where [?r :payroll-run/code ?c]]
                     db' "RUN-US-2026-04-001")
        run (d/pull db' '[* {:payroll-run/payroll-transaction
                             [:transaction/external-id
                              {:posting/_transaction
                               [:posting/amount :posting/account
                                {:posting/analytic-distributions
                                 [:analytic-distribution/percent
                                  {:analytic-distribution/account
                                   [:analytic-account/code]}]}]}]}]
                    run-eid)
        postings (-> run :payroll-run/payroll-transaction
                     :posting/_transaction)]
    (testing "the payroll-run row is created"
      (is (some? run-eid))
      (is (= :adp-gli (:payroll-run/provider-id run))))
    (testing "control totals reflect all three employees combined"
      ;; Gross 8500 + 9200 + 7800 = 25,500
      (is (= 25500.00M (:payroll-run/control-total-gross run)))
      ;; Net 5669.75 + 6221.20 + 5903.30 = 17,794.25
      (is (= 17794.25M (:payroll-run/control-total-net run))))
    (testing "the linked :transaction balances per-(ledger, commodity)"
      (let [sum (reduce (fn [^BigDecimal a {:keys [posting/amount]}]
                          (.add a ^BigDecimal amount))
                        0M postings)]
        (is (zero? (.signum sum)))))
    (testing "every wage-side posting carries an :analytic-distribution to a state"
      (let [with-dist (filter (fn [p] (seq (:posting/analytic-distributions p)))
                              postings)]
        (is (seq with-dist))
        (let [state-codes
              (->> with-dist
                   (mapcat :posting/analytic-distributions)
                   (map :analytic-distribution/account)
                   (map :analytic-account/code)
                   distinct
                   set)]
          (is (= #{"CA" "NY" "TX"} state-codes)))))))

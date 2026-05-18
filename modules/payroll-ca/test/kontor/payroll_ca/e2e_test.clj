(ns kontor.payroll-ca.e2e-test
  "End-to-end bilingual flow per note 84 §10.3 (the C4 acceptance
   criterion). One Acme Canada Inc. with two hires:

     - James MacDonald — ON, EN correspondence
     - Sophie Lavoie   — QC, FR correspondence (QC passthrough)

   Run a payroll cycle through compute → posting → audit-doc.

   Per note 84 §11 Q5 the acceptance criterion is:
     - A CA Inc with N employees posts a payroll via the Ceridian
       CSV adapter through `run-payroll!`.
     - PD7A monthly helper returns correct three-bucket totals.
     - QC employee's T4 boxes 17/17A/55/56 populate; warning logs
       'RL-1 emission deferred to C4.1'.
     - Year-end T619+T4+T4-Summary validates against 2026V4 XSD.

   This file exercises the substrate; the multi-period accrual side
   is exercised in `t4_builder_test/full-submission-validates-against-xsd`."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.hr.compensation :as comp]
            [kontor.hr.core :as hr]
            [kontor.hr.employment :as employment]
            [kontor.hr.pay-period :as pp]
            [kontor.hr.payroll :as payroll]
            [kontor.hr.person :as person]
            [kontor.l10n-ca.chart :as ca-chart]
            [kontor.payroll-ca.chart :as pca-chart]
            [kontor.payroll-ca.emit :as emit]
            [kontor.payroll-ca.pd7a :as pd7a]
            [kontor.payroll-ca.posting-builder :as pb]
            [kontor.payroll-provider :as ppro])
  (:import [java.math BigDecimal]))

(defn- bootstrap []
  (let [conn (core/create-test-db)]
    (hr/install! conn)
    (ca-chart/install! conn)
    (pca-chart/install! conn)
    (d/transact conn
                [{:db/id "ent-acme"
                  :entity/code "ACME-CA"
                  :entity/name "Acme Canada Inc."
                  :entity/kind :operating}
                 {:db/id "journal-pay"
                  :journal/code "PAY-CA"
                  :journal/name "Payroll (CA)"
                  :journal/type :general}
                 {:db/id "period-2026-05"
                  :period/name "2026-05"
                  :period/start #inst "2026-05-01"
                  :period/end #inst "2026-06-01"}])
    conn))

(defn- get-account-eid [db code]
  (d/q '[:find ?e . :in $ ?c :where [?e :account/code ?c]] db code))

;; ============================================================================
;; Mock compute provider — supplies a balanced fact per employment
;; ============================================================================
;; This is the simplest way to drive run-payroll! end-to-end without
;; a CSV fixture. compute-test.clj covers the CSV parser directly.

(defrecord MockCaCompute [opts]
  ppro/PayrollComputeProvider
  (provider-id [_] :mock-ca)
  (compute-payroll [_ {:keys [employment-eids]}]
    (mapv (fn [eid]
            (let [{:keys [province]} (get (:per-emp opts) eid)]
              (case province
                "QC"
                ;; Per note 84 §8 — QC employee has BOTH CRA federal
                ;; deductions AND Revenu Québec parallel ones. For a
                ;; QC employee EI is reduced to 1.31% (vs 1.63%
                ;; federal); CPP is replaced by QPP at the engine
                ;; level for QC. Our mock sends CPP=0 for QC (per
                ;; note 84 §8.1).
                ;; gross 5500
                ;; - 560 fed-itx - 260 qc-itx - 353 qpp - 53 qpip - 82 ei = -1308
                ;; net = 4192
                {:employment eid
                 :gross 5500M
                 :net 4192M
                 :components [{:kind :base-wage          :amount 5500M    :employer-side? false}
                              {:kind :income-tax-withheld :amount -560M   :employer-side? false}
                              {:kind :employee-qc-itx    :amount -260M   :employer-side? false}
                              {:kind :employee-qpp       :amount -353M   :employer-side? false}
                              {:kind :employee-qpip      :amount -53M    :employer-side? false}
                              {:kind :employee-ei        :amount -82M    :employer-side? false}
                              {:kind :employer-qpp       :amount 353M    :employer-side? true}
                              {:kind :employer-qpip      :amount 75M     :employer-side? true}
                              {:kind :employer-ei        :amount 114.80M :employer-side? true}]
                 :jurisdiction-specific-codes
                 {:engine :mock-ca :province-of-employment "QC"
                  :qpip-insurable-earnings 5500M
                  :ei-insurable-earnings   5500M
                  :cpp-pensionable-earnings 5500M}}

                ;; default: ON
                {:employment eid
                 :gross 7083.33M
                 :net 5340.16M
                 :components [{:kind :base-wage          :amount 7083.33M  :employer-side? false}
                              {:kind :income-tax-withheld :amount -1100M   :employer-side? false}
                              {:kind :employee-cpp       :amount -421.46M  :employer-side? false}
                              {:kind :employee-ei        :amount -115.46M  :employer-side? false}
                              {:kind :employee-rpp-contribution :amount -106.25M :employer-side? false}
                              {:kind :employer-cpp       :amount 421.46M   :employer-side? true}
                              {:kind :employer-ei        :amount 161.64M   :employer-side? true}]
                 :jurisdiction-specific-codes
                 {:engine :mock-ca :province-of-employment "ON"
                  :ei-insurable-earnings 7083.33M
                  :cpp-pensionable-earnings 7083.33M}})))
          employment-eids)))

;; ============================================================================
;; The bilingual end-to-end
;; ============================================================================

(deftest bilingual-payroll-end-to-end
  (let [conn (bootstrap)
        db (d/db conn)
        cad (d/q '[:find ?e . :where [?e :commodity/symbol "CAD"]] db)
        ent (d/q '[:find ?e . :where [?e :entity/code "ACME-CA"]] db)
        journal (d/q '[:find ?e . :where [?e :journal/code "PAY-CA"]] db)
        period (d/q '[:find ?e . :where [?e :period/name "2026-05"]] db)
        ;; Persons + employments
        _ (person/create-person!
           conn {:external-id "P-james"
                 :given-name "James" :family-name "MacDonald"})
        _ (person/create-person!
           conn {:external-id "P-sophie"
                 :given-name "Sophie" :family-name "Lavoie"})
        db (d/db conn)
        james (hr/person-by-external-id db "P-james")
        sophie (hr/person-by-external-id db "P-sophie")
        _ (employment/hire! conn {:code "EMP-james"
                                  :person james :entity ent
                                  :start-date #inst "2026-01-15"
                                  :job-title "Senior Eng"})
        _ (employment/hire! conn {:code "EMP-sophie"
                                  :person sophie :entity ent
                                  :start-date #inst "2026-01-15"
                                  :job-title "Designer"})
        db (d/db conn)
        james-emp (hr/employment-by-code db "EMP-james")
        sophie-emp (hr/employment-by-code db "EMP-sophie")
        _ (comp/set-compensation!
           conn {:employment james-emp
                 :effective-from #inst "2026-01-15"
                 :commodity cad
                 :components [{:kind :base-wage :amount 85000M :period :annual}]})
        _ (comp/set-compensation!
           conn {:employment sophie-emp
                 :effective-from #inst "2026-01-15"
                 :commodity cad
                 :components [{:kind :base-wage :amount 66000M :period :annual}]})
        _ (pp/create-pay-period!
           conn {:code "ACME-2026-05" :entity ent
                 :start-date #inst "2026-05-01"
                 :end-date #inst "2026-05-31"
                 :frequency :monthly
                 :fiscal-period period})
        pp-eid (hr/pay-period-by-code (d/db conn) "ACME-2026-05")
        ;; Account refs by tag → eid for the posting builder
        db (d/db conn)
        accounts {:ca-payroll-wages              (get-account-eid db "5400")
                  :ca-payroll-er-cpp             (get-account-eid db "5410")
                  :ca-payroll-er-ei              (get-account-eid db "5411")
                  :ca-payroll-vacation-accrual   (get-account-eid db "5412")
                  :ca-payroll-er-rpp             (get-account-eid db "5413")
                  :ca-payroll-itx                (get-account-eid db "2510")
                  :ca-payroll-cpp                (get-account-eid db "2520")
                  :ca-payroll-ei                 (get-account-eid db "2530")
                  :ca-payroll-rpp                (get-account-eid db "2560")
                  :ca-payroll-vacation-liability (get-account-eid db "2540")
                  :ca-payroll-net-wages          (get-account-eid db "2550")
                  :ca-payroll-qpp                (get-account-eid db "2521")
                  :ca-payroll-qpip               (get-account-eid db "2531")
                  :ca-payroll-qc-itx             (get-account-eid db "2511")}
        compute-provider (->MockCaCompute
                          {:per-emp {james-emp {:province "ON"}
                                     sophie-emp {:province "QC"}}})
        posting-builder (pb/->CaPayrollPostingBuilder
                         {:commodity cad
                          :rp-account-tag nil}) ; no per-RP routing in this test
        emit-provider (emit/->CaPayrollEmitProvider {:language :en})
        report (payroll/run-payroll!
                conn {:pay-period pp-eid
                      :entity ent
                      :employments [james-emp sophie-emp]
                      :compute-provider compute-provider
                      :posting-builder posting-builder
                      :emit-provider emit-provider
                      :accounts accounts
                      :run-code "ACME-2026-05-001"
                      :tx-code "TX-ACME-2026-05"
                      :journal journal
                      :commodity cad})
        db (:db-after report)
        run-eid (d/q '[:find ?r . :in $ ?c
                       :where [?r :payroll-run/code ?c]]
                     db "ACME-2026-05-001")
        run (d/pull db '[* {:payroll-run/payroll-transaction
                            [:transaction/external-id
                             {:posting/_transaction
                              [:posting/amount
                               {:posting/account [:account/code]}]}]}]
                    run-eid)]
    (testing "payroll-run row created"
      (is (some? run-eid))
      (is (= :computed (:payroll-run/state run)))
      (is (= :mock-ca (:payroll-run/provider-id run))))
    (testing "Control totals reflect both employees"
      ;; James gross 7083.33 + Sophie gross 5500 = 12583.33
      (is (= 12583.33M (:payroll-run/control-total-gross run))))
    (testing "Posting legs sum to zero per ledger × commodity"
      (let [postings (-> run :payroll-run/payroll-transaction
                         :posting/_transaction)
            sum (reduce (fn [a {:keys [posting/amount]}]
                          (.add ^BigDecimal a ^BigDecimal amount))
                        0M postings)]
        (is (zero? (.compareTo ^BigDecimal sum 0M)))))
    (testing "Sophie's QC-specific deductions hit the right accounts"
      (let [postings (-> run :payroll-run/payroll-transaction
                         :posting/_transaction)
            by-code (group-by (comp :account/code :posting/account) postings)]
        ;; QPP (2521) total: -353 employee + -353 employer payable
        (is (= -706M
               (reduce (fn [a {:keys [posting/amount]}]
                         (.add ^BigDecimal a ^BigDecimal amount))
                       0M (get by-code "2521"))))
        ;; QPIP (2531) total: -53 employee + -75 employer payable
        (is (= -128M
               (reduce (fn [a {:keys [posting/amount]}]
                         (.add ^BigDecimal a ^BigDecimal amount))
                       0M (get by-code "2531"))))
        ;; QC ITX (2511): -260 employee only (no employer parallel)
        (is (= -260M
               (reduce (fn [a {:keys [posting/amount]}]
                         (.add ^BigDecimal a ^BigDecimal amount))
                       0M (get by-code "2511"))))))
    (testing "PD7A helper computes the three CRA buckets cleanly"
      (let [summary (pd7a/pd7a-period-due
                     conn {:period-start #inst "2026-05-01"
                           :period-end   #inst "2026-06-01"
                           :remitter-type :regular})]
        ;; Federal ITX (only — QC ITX goes to RQ, NOT CRA):
        ;;   James 1100 + Sophie 560 = 1660
        (is (= 1660M (:amount (:itx summary))))
        ;; CRA CPP (James only — Sophie's QPP is in 2521, NOT 2520):
        ;;   James employee 421.46 + employer 421.46 = 842.92
        (is (= 842.92M (:amount (:cpp summary))))
        ;; CRA EI (Federal — both employees use 2530):
        ;;   James employee 115.46 + employer 161.64 = 277.10
        ;;   Sophie employee 82 + employer 114.80 = 196.80
        ;;   Total = 473.90
        (is (= 473.90M (:amount (:ei summary))))))
    (testing "Emit provider produced one audit-doc with :payroll-filing category"
      ;; Note 86 P0-86-2 — canonical vocabulary; was :payroll.
      (let [docs (d/q '[:find [?e ...]
                        :where [?e :audit-doc/category :payroll-filing]]
                      db)]
        (is (>= (count docs) 1))))))

;; ============================================================================
;; QC warning
;; ============================================================================

(deftest qc-passthrough-warns
  (let [facts [{:employment :emp/sophie
                :gross 5500M :net 3800M
                :components [{:kind :base-wage    :amount 5500M  :employer-side? false}
                             {:kind :employee-qpp :amount -353M  :employer-side? false}]
                :jurisdiction-specific-codes {}}]
        qc-set (emit/warn-if-qc-detected! facts)]
    (testing "Sophie was detected as QC"
      (is (= #{:emp/sophie} qc-set)))))

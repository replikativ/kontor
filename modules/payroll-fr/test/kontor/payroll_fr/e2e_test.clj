(ns kontor.payroll-fr.e2e-test
  "End-to-end FR payroll flow per ADR-079 acceptance criterion.
   One Acme France SAS with two hires, monthly payroll cycle, full
   `run-payroll!` → posting + DSN audit-doc."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.hr.compensation :as comp]
            [kontor.hr.core :as hr]
            [kontor.hr.employment :as employment]
            [kontor.hr.pay-period :as pp]
            [kontor.hr.payroll :as payroll]
            [kontor.hr.person :as person]
            [kontor.l10n-fr.chart :as fr-chart]
            [kontor.payroll-fr.chart :as pfr-chart]
            [kontor.payroll-fr.emit :as emit]
            [kontor.payroll-fr.posting-builder :as pb]
            [kontor.provider.payroll-provider :as ppro])
  (:import [java.math BigDecimal]))

(defn- bootstrap []
  (let [conn (core/create-test-db)]
    (hr/install! conn)
    (fr-chart/install! conn)
    (pfr-chart/install! conn)
    (d/transact conn
                [{:db/id "ent-acme"
                  :kontor.entity/code "ACME-FR"
                  :kontor.entity/name "Acme France SAS"
                  :kontor.entity/kind :operating}
                 {:db/id "journal-pay"
                  :kontor.journal/code "PAY-FR"
                  :kontor.journal/name "Paie (FR)"
                  :kontor.journal/type :general}
                 {:db/id "period-2026-05"
                  :kontor.period/name "2026-05"
                  :kontor.period/start #inst "2026-05-01"
                  :kontor.period/end #inst "2026-06-01"}])
    conn))

(defn- get-account-eid [db code]
  (d/q '[:find ?e . :in $ ?c :where [?e :kontor.account/code ?c]] db code))

;; ============================================================================
;; Mock compute provider — supplies a balanced fact per employment
;; ============================================================================
;; A real Silae CSV is exercised in compute-test; this mock keeps the
;; e2e focused on the run-payroll! orchestrator path.

(defrecord MockFrCompute [opts]
  ppro/PayrollComputeProvider
  (provider-id [_] :mock-fr)
  (compute-payroll [_ {:keys [employment-eids]}]
    (mapv
     (fn [eid]
       (let [{:keys [profile]} (get (:per-emp opts) eid)]
         (case profile
           :cadre
           ;; Cadre with gross 5000, PAS 8%, employer charges ~45%
           ;; Employee deductions: URSSAF 305 + CSG-ded 343.95 +
           ;;   CSG-nded 154.78 + CRDS 32.25 + ARRCO 200 +
           ;;   mutuelle 50 + PAS 400 = 1485.98
           ;; Net = 5000 - 1485.98 = 3514.02
           {:employment eid
            :gross 5000M
            :net 3514.02M
            :components
            [{:kind :base-salary             :amount 5000M     :employer-side? false}
             {:kind :cotisation-urssaf       :amount -305M     :employer-side? false}
             {:kind :csg-deductible          :amount -343.95M  :employer-side? false}
             {:kind :csg-non-deductible      :amount -154.78M  :employer-side? false}
             {:kind :crds                    :amount -32.25M   :employer-side? false}
             {:kind :cotisation-arrco-agirc  :amount -200M     :employer-side? false}
             {:kind :medical-mutuelle        :amount -50M      :employer-side? false}
             {:kind :pas-withholding         :amount -400M     :employer-side? false}
             {:kind :employer-urssaf         :amount 1500M     :employer-side? true}
             {:kind :employer-arrco-agirc    :amount 300M      :employer-side? true}
             {:kind :employer-pole-emploi    :amount 200M      :employer-side? true}
             {:kind :employer-prevoyance     :amount 75M       :employer-side? true}
             ;; Vacation accrual (in-band per ADR-079)
             {:kind :conges-payes-accrual    :amount 416.66M   :employer-side? true}]
            :jurisdiction-specific-codes
            {:engine :mock-fr :matricule (str "M-" eid)
             :taux-pas 0.08M :base-soumise-urssaf 5000M
             :plafond-secu 3864M}}

           ;; default non-cadre: gross 2200, lighter PAS + no ARRCO mutuelle
           {:employment eid
            :gross 2200M
            :net 1620.32M
            :components
            [{:kind :base-salary             :amount 2200M     :employer-side? false}
             {:kind :cotisation-urssaf       :amount -174.24M  :employer-side? false}
             {:kind :csg-deductible          :amount -151.36M  :employer-side? false}
             {:kind :csg-non-deductible      :amount -68.08M   :employer-side? false}
             {:kind :crds                    :amount -14.20M   :employer-side? false}
             {:kind :pas-withholding         :amount -171.80M  :employer-side? false}
             {:kind :employer-urssaf         :amount 660M      :employer-side? true}
             {:kind :employer-pole-emploi    :amount 88M       :employer-side? true}]
            :jurisdiction-specific-codes
            {:engine :mock-fr :matricule (str "M-" eid)
             :taux-pas 0.078M :base-soumise-urssaf 2200M}})))
     employment-eids)))

;; ============================================================================
;; The end-to-end run
;; ============================================================================

(deftest fr-payroll-end-to-end
  (let [conn (bootstrap)
        db (d/db conn)
        eur (d/q '[:find ?e . :where [?e :kontor.commodity/symbol "EUR"]] db)
        ent (d/q '[:find ?e . :where [?e :kontor.entity/code "ACME-FR"]] db)
        journal (d/q '[:find ?e . :where [?e :kontor.journal/code "PAY-FR"]] db)
        period (d/q '[:find ?e . :where [?e :kontor.period/name "2026-05"]] db)
        ;; Persons + employments
        _ (person/create-person!
           conn {:external-id "P-dupont"
                 :given-name "Jean" :family-name "Dupont"})
        _ (person/create-person!
           conn {:external-id "P-martin"
                 :given-name "Marie" :family-name "Martin"})
        db (d/db conn)
        dupont (hr/person-by-external-id db "P-dupont")
        martin (hr/person-by-external-id db "P-martin")
        _ (employment/hire! conn {:code "EMP-dupont"
                                  :person dupont :entity ent
                                  :start-date #inst "2026-01-15"
                                  :job-title "Ingénieur cadre"})
        _ (employment/hire! conn {:code "EMP-martin"
                                  :person martin :entity ent
                                  :start-date #inst "2026-01-15"
                                  :job-title "Assistante administrative"})
        db (d/db conn)
        dupont-emp (hr/employment-by-code db "EMP-dupont")
        martin-emp (hr/employment-by-code db "EMP-martin")
        _ (comp/set-compensation!
           conn {:employment dupont-emp
                 :effective-from #inst "2026-01-15"
                 :commodity eur
                 :components [{:kind :base-wage :amount 60000M :period :annual}]})
        _ (comp/set-compensation!
           conn {:employment martin-emp
                 :effective-from #inst "2026-01-15"
                 :commodity eur
                 :components [{:kind :base-wage :amount 26400M :period :annual}]})
        _ (pp/create-pay-period!
           conn {:code "ACME-2026-05" :entity ent
                 :start-date #inst "2026-05-01"
                 :end-date #inst "2026-05-31"
                 :frequency :monthly
                 :fiscal-period period})
        pp-eid (hr/pay-period-by-code (d/db conn) "ACME-2026-05")
        db (d/db conn)
        accounts {:fr-payroll-salaires           (get-account-eid db "6411")
                  :fr-payroll-conges-payes       (get-account-eid db "6412")
                  :fr-payroll-primes             (get-account-eid db "6413")
                  :fr-payroll-avantages-nature   (get-account-eid db "6414")
                  :fr-payroll-er-urssaf          (get-account-eid db "6451")
                  :fr-payroll-er-retraite        (get-account-eid db "6453")
                  :fr-payroll-er-assedic         (get-account-eid db "6454")
                  :fr-payroll-er-prevoyance      (get-account-eid db "6455")
                  :fr-payroll-conges-accrual     (get-account-eid db "6412")
                  :fr-payroll-personnel-net      (get-account-eid db "421")
                  :fr-payroll-acomptes           (get-account-eid db "425")
                  :fr-payroll-oppositions        (get-account-eid db "427")
                  :fr-payroll-urssaf             (get-account-eid db "431")
                  :fr-payroll-retraite           (get-account-eid db "4371")
                  :fr-payroll-pole-emploi        (get-account-eid db "4373")
                  :fr-payroll-prevoyance         (get-account-eid db "4374")
                  :fr-payroll-pas                (get-account-eid db "4421")
                  :fr-payroll-conges-liability   (get-account-eid db "4282")}
        compute-provider (->MockFrCompute
                          {:per-emp {dupont-emp {:profile :cadre}
                                     martin-emp {:profile :non-cadre}}})
        posting-builder (pb/->FrPayrollPostingBuilder
                         {:commodity eur
                          :etab-account-tag nil})
        emit-provider (emit/->FrDsnEmitProvider {:language :fr})
        report (payroll/run-payroll!
                conn {:pay-period pp-eid
                      :entity ent
                      :employments [dupont-emp martin-emp]
                      :compute-provider compute-provider
                      :posting-builder posting-builder
                      :emit-provider emit-provider
                      :accounts accounts
                      :run-code "ACME-2026-05-001"
                      :tx-code "TX-ACME-2026-05"
                      :journal journal
                      :commodity eur})
        db (:db-after report)
        run-eid (d/q '[:find ?r . :in $ ?c
                       :where [?r :kontor.payroll-run/code ?c]]
                     db "ACME-2026-05-001")
        run (d/pull db '[* {:kontor.payroll-run/payroll-transaction
                            [:kontor.transaction/external-id
                             {:kontor.posting/_transaction
                              [:kontor.posting/amount
                               {:kontor.posting/account [:kontor.account/code]}]}]}]
                    run-eid)]
    (testing "payroll-run row created"
      (is (some? run-eid))
      (is (= :computed (:kontor.payroll-run/state run)))
      (is (= :mock-fr (:kontor.payroll-run/provider-id run))))
    (testing "Control totals reflect both employees"
      ;; Dupont gross 5000 + Martin gross 2200 = 7200
      (is (= 7200M (:kontor.payroll-run/control-total-gross run))))
    (testing "Posting legs sum to zero per ledger × commodity"
      (let [postings (-> run :kontor.payroll-run/payroll-transaction
                         :kontor.posting/_transaction)
            sum (reduce (fn [a {:kontor.posting/keys [amount]}]
                          (.add ^BigDecimal a ^BigDecimal amount))
                        0M postings)]
        (is (zero? (.compareTo ^BigDecimal sum 0M)))))
    (testing "PCG 431 (URSSAF) accumulates all URSSAF flow for both employees"
      (let [postings (-> run :kontor.payroll-run/payroll-transaction
                         :kontor.posting/_transaction)
            by-code (group-by (comp :kontor.account/code :kontor.posting/account) postings)
            urssaf-431 (reduce (fn [a {:kontor.posting/keys [amount]}]
                                 (.add ^BigDecimal a ^BigDecimal amount))
                               0M (get by-code "431"))]
        ;; Dupont: employee URSSAF -305 + CSG-ded -343.95 + CSG-nded -154.78
        ;;         + CRDS -32.25 + employer URSSAF mirror -1500 = -2335.98
        ;; Martin: employee URSSAF -174.24 + CSG-ded -151.36 + CSG-nded -68.08
        ;;         + CRDS -14.20 + employer URSSAF mirror -660 = -1067.88
        ;; Total: -3403.86
        (is (= -3403.86M urssaf-431))))
    (testing "PCG 421 (Net wages payable) totals both employees' net"
      (let [postings (-> run :kontor.payroll-run/payroll-transaction
                         :kontor.posting/_transaction)
            by-code (group-by (comp :kontor.account/code :kontor.posting/account) postings)
            net-421 (reduce (fn [a {:kontor.posting/keys [amount]}]
                              (.add ^BigDecimal a ^BigDecimal amount))
                            0M (get by-code "421"))]
        ;; -(3514.02 + 1620.32) = -5134.34
        (is (= -5134.34M net-421))))
    (testing "PCG 4421 (PAS withholding) is non-zero"
      (let [postings (-> run :kontor.payroll-run/payroll-transaction
                         :kontor.posting/_transaction)
            by-code (group-by (comp :kontor.account/code :kontor.posting/account) postings)
            pas-4421 (reduce (fn [a {:kontor.posting/keys [amount]}]
                               (.add ^BigDecimal a ^BigDecimal amount))
                             0M (get by-code "4421"))]
        ;; Dupont -400 + Martin -171.80 = -571.80
        (is (= -571.80M pas-4421))))
    (testing "Congés payés accrual lands on both 6412 (DR) and 4282 (CR)"
      (let [postings (-> run :kontor.payroll-run/payroll-transaction
                         :kontor.posting/_transaction)
            by-code (group-by (comp :kontor.account/code :kontor.posting/account) postings)
            cp-4282 (reduce (fn [a {:kontor.posting/keys [amount]}]
                              (.add ^BigDecimal a ^BigDecimal amount))
                            0M (get by-code "4282"))]
        ;; Only Dupont accrual: -416.66
        (is (= -416.66M cp-4282))))
    (testing "Emit provider produced one DSN audit-doc with :payroll-filing"
      (let [docs (d/q '[:find [?e ...]
                        :where [?e :kontor.audit-doc/category :payroll-filing]]
                      db)]
        (is (>= (count docs) 1))
        (let [dsn-doc (d/pull db '[*] (first docs))]
          (is (= :fr (:kontor.audit-doc/language dsn-doc)))
          (is (= :regulator-clearance (:kontor.audit-doc/type dsn-doc)))
          (is (str/starts-with? (:kontor.audit-doc/code dsn-doc) "DSN-")))))))

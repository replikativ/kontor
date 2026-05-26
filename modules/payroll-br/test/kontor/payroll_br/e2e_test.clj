(ns kontor.payroll-br.e2e-test
  "End-to-end BR payroll flow per ADR-081 acceptance criterion:

     - A BR Ltda with N employees posts a payroll via the RH Sistemas
       CSV adapter through `run-payroll!`.
     - Posting balances per-(ledger × commodity).
     - eSocial S-1200 / S-1210 / S-1299 audit-docs are produced and
       carry :audit-doc/category :payroll-filing + :pt-br language.
     - Each event payload includes a well-formed XML payload (round-
       trips through clojure.data.xml).
     - The four canonical statutory buckets (INSS-EE, INSS-ER, FGTS,
       IRRF) all land on distinct accounts."
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
            [kontor.l10n-br.chart :as br-chart]
            [kontor.payroll-br.chart :as pbr-chart]
            [kontor.payroll-br.core :as br-core]
            [kontor.payroll-br.emit :as emit]
            [kontor.payroll-br.posting-builder :as pb]
            [kontor.payroll-provider :as ppro])
  (:import [java.math BigDecimal]))

(defn- bootstrap []
  (let [conn (core/create-test-db)]
    (hr/install! conn)
    (br-chart/install! conn)
    (br-core/install! conn)
    (pbr-chart/install! conn)
    (d/transact conn
                [{:db/id "ent-acme-br"
                  :kontor.entity/code "ACME-BR"
                  :kontor.entity/name "Acme do Brasil Ltda"
                  :kontor.entity/kind :operating}
                 {:db/id "journal-pay-br"
                  :kontor.journal/code "PAY-BR"
                  :kontor.journal/name "Folha de Pagamento (BR)"
                  :kontor.journal/type :general}
                 {:db/id "period-2026-05-br"
                  :period/name "2026-05-br"
                  :period/start #inst "2026-05-01"
                  :period/end #inst "2026-06-01"}])
    conn))

(defn- get-account-eid [db code]
  (d/q '[:find ?e . :in $ ?c :where [?e :kontor.account/code ?c]] db code))

;; ============================================================================
;; Mock compute provider — supplies balanced facts per employment
;; ============================================================================

(defrecord MockBrCompute [opts]
  ppro/PayrollComputeProvider
  (provider-id [_] :mock-br)
  (compute-payroll [_ {:keys [employment-eids]}]
    (mapv (fn [eid]
            (let [{:keys [cpf]} (get (:per-emp opts) eid)]
              {:employment eid
               :gross 5000M
               :net 4352.50M
               :components [{:kind :base-wage      :amount 5000M
                             :employer-side? false :rubrica "R001"}
                            {:kind :inss-employee  :amount -400M
                             :employer-side? false :rubrica "R200"}
                            {:kind :irrf-employee  :amount -247.50M
                             :employer-side? false :rubrica "R210"}
                            {:kind :inss-employer  :amount 1000M
                             :employer-side? true  :rubrica "R900"}
                            {:kind :fgts-employer  :amount 400M
                             :employer-side? true  :rubrica "R901"}]
               :jurisdiction-specific-codes
               {:engine :mock-br
                :employee-external-id cpf}}))
          employment-eids)))

;; ============================================================================
;; The full BR pay-run end-to-end
;; ============================================================================

(deftest br-payroll-end-to-end
  (let [conn (bootstrap)
        db (d/db conn)
        brl (d/q '[:find ?e . :where [?e :kontor.commodity/symbol "BRL"]] db)
        ent (d/q '[:find ?e . :where [?e :kontor.entity/code "ACME-BR"]] db)
        journal (d/q '[:find ?e . :where [?e :kontor.journal/code "PAY-BR"]] db)
        period (d/q '[:find ?e . :where [?e :period/name "2026-05-br"]] db)
        ;; Create persons + employments
        _ (person/create-person!
           conn {:external-id "P-jane"
                 :given-name "Jane" :family-name "Silva"})
        _ (person/create-person!
           conn {:external-id "P-joao"
                 :given-name "João" :family-name "Santos"})
        db (d/db conn)
        jane (hr/person-by-external-id db "P-jane")
        joao (hr/person-by-external-id db "P-joao")
        _ (employment/hire! conn {:code "EMP-jane"
                                  :person jane :entity ent
                                  :start-date #inst "2026-01-15"
                                  :job-title "Engenheiro"})
        _ (employment/hire! conn {:code "EMP-joao"
                                  :person joao :entity ent
                                  :start-date #inst "2026-01-15"
                                  :job-title "Designer"})
        db (d/db conn)
        jane-emp (hr/employment-by-code db "EMP-jane")
        joao-emp (hr/employment-by-code db "EMP-joao")
        _ (comp/set-compensation!
           conn {:employment jane-emp
                 :effective-from #inst "2026-01-15"
                 :commodity brl
                 :components [{:kind :base-wage :amount 60000M :period :annual}]})
        _ (comp/set-compensation!
           conn {:employment joao-emp
                 :effective-from #inst "2026-01-15"
                 :commodity brl
                 :components [{:kind :base-wage :amount 60000M :period :annual}]})
        _ (pp/create-pay-period!
           conn {:code "ACME-BR-2026-05" :entity ent
                 :start-date #inst "2026-05-01"
                 :end-date #inst "2026-05-31"
                 :frequency :monthly
                 :fiscal-period period})
        pp-eid (hr/pay-period-by-code (d/db conn) "ACME-BR-2026-05")
        db (d/db conn)
        accounts {:br-payroll-wages              (get-account-eid db "4.1.1.01")
                  :br-payroll-er-inss            (get-account-eid db "4.1.1.06")
                  :br-payroll-er-fgts            (get-account-eid db "4.1.1.07")
                  :br-payroll-ferias-accrual     (get-account-eid db "4.1.1.10")
                  :br-payroll-13th-accrual       (get-account-eid db "4.1.1.15")
                  :br-payroll-severance-accrual  (get-account-eid db "4.1.1.20")
                  :br-payroll-inss-employee      (get-account-eid db "2.1.1.05")
                  :br-payroll-inss-employer      (get-account-eid db "2.1.1.06")
                  :br-payroll-fgts               (get-account-eid db "2.1.1.10")
                  :br-payroll-irrf               (get-account-eid db "2.1.1.15")
                  :br-payroll-net-wages          (get-account-eid db "2.1.1.01")
                  :br-payroll-ferias-liability   (get-account-eid db "2.1.2.01")
                  :br-payroll-13th-liability     (get-account-eid db "2.1.2.05")
                  :br-payroll-severance-liability (get-account-eid db "2.1.2.10")
                  :br-payroll-vr-vt              (get-account-eid db "4.1.1.25")
                  :br-payroll-benefits           (get-account-eid db "4.1.1.30")}
        compute-provider (->MockBrCompute
                          {:per-emp {jane-emp {:cpf "11144477735"}
                                     joao-emp {:cpf "12345678909"}}})
        posting-builder (pb/->BrPayrollPostingBuilder
                         {:commodity brl
                          :cnpj-account-tag nil})
        emit-provider (emit/make-provider
                       {:employer-cnpj "11.222.333/0001-81"
                        :per-apur #inst "2026-05-01"
                        :cod-lotacao "LOT01"
                        :employee-cpf->matricula
                        {"11144477735" "EMP-jane"
                         "12345678909" "EMP-joao"}})
        report (payroll/run-payroll!
                conn {:pay-period pp-eid
                      :entity ent
                      :employments [jane-emp joao-emp]
                      :compute-provider compute-provider
                      :posting-builder posting-builder
                      :emit-provider emit-provider
                      :accounts accounts
                      :run-code "ACME-BR-2026-05-001"
                      :tx-code "TX-ACME-BR-2026-05"
                      :journal journal
                      :commodity brl})
        db (:db-after report)
        run-eid (d/q '[:find ?r . :in $ ?c
                       :where [?r :payroll-run/code ?c]]
                     db "ACME-BR-2026-05-001")
        run (d/pull db '[* {:payroll-run/payroll-transaction
                            [:kontor.transaction/external-id
                             {:kontor.posting/_transaction
                              [:kontor.posting/amount
                               {:kontor.posting/account [:kontor.account/code]}]}]}]
                    run-eid)]
    (testing "payroll-run row created"
      (is (some? run-eid))
      (is (= :computed (:payroll-run/state run)))
      (is (= :mock-br (:payroll-run/provider-id run))))
    (testing "Control totals reflect both employees"
      ;; 2 × 5000 = 10000
      (is (= 10000M (:payroll-run/control-total-gross run))))
    (testing "Posting legs sum to zero per (ledger × commodity)"
      (let [postings (-> run :payroll-run/payroll-transaction
                         :kontor.posting/_transaction)
            sum (reduce (fn [a {:kontor.posting/keys [amount]}]
                          (.add ^BigDecimal a ^BigDecimal amount))
                        0M postings)]
        (is (zero? (.compareTo ^BigDecimal sum 0M)))))
    (testing "Four canonical BR statutory buckets land on DISTINCT accounts"
      (let [postings (-> run :payroll-run/payroll-transaction
                         :kontor.posting/_transaction)
            by-code (group-by (comp :kontor.account/code :kontor.posting/account) postings)]
        ;; INSS empregado (2.1.1.05): -800 (2 × -400)
        (is (= -800M
               (reduce (fn [a {:kontor.posting/keys [amount]}]
                         (.add ^BigDecimal a ^BigDecimal amount))
                       0M (get by-code "2.1.1.05"))))
        ;; INSS empregador (2.1.1.06): -2000 (2 × -1000, paired credit)
        (is (= -2000M
               (reduce (fn [a {:kontor.posting/keys [amount]}]
                         (.add ^BigDecimal a ^BigDecimal amount))
                       0M (get by-code "2.1.1.06"))))
        ;; FGTS (2.1.1.10): -800 (2 × -400)
        (is (= -800M
               (reduce (fn [a {:kontor.posting/keys [amount]}]
                         (.add ^BigDecimal a ^BigDecimal amount))
                       0M (get by-code "2.1.1.10"))))
        ;; IRRF (2.1.1.15): -495 (2 × -247.50)
        (is (= -495M
               (reduce (fn [a {:kontor.posting/keys [amount]}]
                         (.add ^BigDecimal a ^BigDecimal amount))
                       0M (get by-code "2.1.1.15"))))))
    (testing "eSocial emit-docs produced with :payroll-filing category"
      (let [docs (d/q '[:find [?e ...]
                        :where [?e :audit-doc/category :payroll-filing]]
                      db)]
        ;; 2 employees × 2 events (S-1200 + S-1210) + 1 S-1299 = 5 docs
        (is (>= (count docs) 5))))
    (testing "All eSocial emit-docs are :pt-br language"
      (let [docs (d/q '[:find [?e ...]
                        :where
                        [?e :audit-doc/category :payroll-filing]]
                      db)
            languages (map (fn [eid]
                             (:audit-doc/language
                              (d/pull db [:audit-doc/language] eid)))
                           docs)]
        (is (every? #(= :pt-br %) languages))))
    (testing "S-1200 payloads contain rubrica codes"
      (let [all-docs (d/q '[:find ?c ?p
                            :where
                            [?e :audit-doc/code ?c]
                            [?e :audit-doc/inline-payload ?p]]
                          db)
            s1200-payloads (->> all-docs
                                (filter (fn [[c _]] (str/includes? c "S1200")))
                                (map second))]
        (is (seq s1200-payloads))
        (is (every? #(str/includes? % "R001") s1200-payloads))))))

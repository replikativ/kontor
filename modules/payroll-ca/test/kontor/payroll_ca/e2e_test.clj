(ns kontor.payroll-ca.e2e-test
  "End-to-end bilingual flow per note 84 §10.3 (the C4 acceptance
   criterion) + ADR-087 (C4.1 RL-1 emission). One Acme Canada Inc.
   with two hires:

     - James MacDonald — ON, EN correspondence
     - Sophie Lavoie   — QC, FR correspondence

   Run a payroll cycle through compute → posting → audit-doc.

   Per ADR-087 the C4.1 acceptance criterion extends the C4 one:
     - A CA Inc with N employees posts a payroll via the Ceridian
       CSV adapter through `run-payroll!`.
     - PD7A monthly helper returns correct three-bucket CRA totals.
     - TPZ-1015 monthly helper returns correct four-bucket Revenu
       Québec totals (QC-ITX / QPP / QPIP / FSS).
     - QC employee's T4 boxes 17/17A/55/56 populate.
     - QC employee's RL-1 slip + RL-1 Summary builds without warning
       (warning fires only when the QC emitter is NOT installed).
     - Year-end T619+T4+T4-Summary validates against 2026V4 XSD.

   This file exercises the substrate; the multi-period accrual side
   is exercised in `t4_builder_test/full-submission-validates-against-xsd`."
  (:require [clojure.data.xml :as xml]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.hr.compensation :as comp]
            [kontor.hr.core :as hr]
            [kontor.hr.employment :as employment]
            [kontor.hr.pay-period :as pp]
            [kontor.hr.payroll :as payroll]
            [kontor.hr.person :as person]
            [kontor.l10n-ca.chart :as ca-chart]
            [kontor.money :as money]
            [kontor.payroll-ca.chart :as pca-chart]
            [kontor.payroll-ca.emit :as emit]
            [kontor.payroll-ca.pd7a :as pd7a]
            [kontor.payroll-ca.posting-builder :as pb]
            [kontor.payroll-ca.qc-emit :as qc-emit]
            [kontor.payroll-ca.tpz1015 :as tpz1015]
            [kontor.payroll-provider :as ppro])
  (:import [java.math BigDecimal]))

(defn- bootstrap []
  (let [conn (core/create-test-db)]
    (hr/install! conn)
    (ca-chart/install! conn)
    (pca-chart/install! conn)
    (d/transact conn
                [{:db/id "ent-acme"
                  :kontor.entity/code "ACME-CA"
                  :kontor.entity/name "Acme Canada Inc."
                  :kontor.entity/kind :operating}
                 {:db/id "journal-pay"
                  :kontor.journal/code "PAY-CA"
                  :kontor.journal/name "Payroll (CA)"
                  :kontor.journal/type :general}
                 {:db/id "period-2026-05"
                  :period/name "2026-05"
                  :period/start #inst "2026-05-01"
                  :period/end #inst "2026-06-01"}])
    conn))

(defn- get-account-eid [db code]
  (d/q '[:find ?e . :in $ ?c :where [?e :kontor.account/code ?c]] db code))

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
                              {:kind :employer-fss       :amount 215M    :employer-side? true}
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
        cad (d/q '[:find ?e . :where [?e :kontor.commodity/symbol "CAD"]] db)
        ent (d/q '[:find ?e . :where [?e :kontor.entity/code "ACME-CA"]] db)
        journal (d/q '[:find ?e . :where [?e :kontor.journal/code "PAY-CA"]] db)
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
                  :ca-payroll-qc-itx             (get-account-eid db "2511")
                  :ca-payroll-fss                (get-account-eid db "2532")
                  :ca-payroll-er-fss             (get-account-eid db "5417")}
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
                            [:kontor.transaction/external-id
                             {:kontor.posting/_transaction
                              [:kontor.posting/amount
                               {:kontor.posting/account [:kontor.account/code]}]}]}]
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
                         :kontor.posting/_transaction)
            sum (reduce (fn [a {:kontor.posting/keys [amount]}]
                          (.add ^BigDecimal a ^BigDecimal amount))
                        0M postings)]
        (is (zero? (.compareTo ^BigDecimal sum 0M)))))
    (testing "Sophie's QC-specific deductions hit the right accounts"
      (let [postings (-> run :payroll-run/payroll-transaction
                         :kontor.posting/_transaction)
            by-code (group-by (comp :kontor.account/code :kontor.posting/account) postings)]
        ;; QPP (2521) total: -353 employee + -353 employer payable
        (is (= -706M
               (reduce (fn [a {:kontor.posting/keys [amount]}]
                         (.add ^BigDecimal a ^BigDecimal amount))
                       0M (get by-code "2521"))))
        ;; QPIP (2531) total: -53 employee + -75 employer payable
        (is (= -128M
               (reduce (fn [a {:kontor.posting/keys [amount]}]
                         (.add ^BigDecimal a ^BigDecimal amount))
                       0M (get by-code "2531"))))
        ;; QC ITX (2511): -260 employee only (no employer parallel)
        (is (= -260M
               (reduce (fn [a {:kontor.posting/keys [amount]}]
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
;; QC detection — passthrough vs. emitter-installed
;; ============================================================================

(deftest qc-passthrough-warns-without-emitter
  (let [facts [{:employment :emp/sophie
                :gross 5500M :net 3800M
                :components [{:kind :base-wage    :amount 5500M  :employer-side? false}
                             {:kind :employee-qpp :amount -353M  :employer-side? false}]
                :jurisdiction-specific-codes {}}]
        qc-set (emit/warn-if-qc-detected! facts)]
    (testing "Sophie was detected as QC"
      (is (= #{:emp/sophie} qc-set)))))

(deftest qc-no-warn-when-emitter-installed
  (let [facts [{:employment :emp/sophie
                :gross 5500M :net 3800M
                :components [{:kind :base-wage    :amount 5500M  :employer-side? false}
                             {:kind :employee-qpp :amount -353M  :employer-side? false}]
                :jurisdiction-specific-codes {}}]
        sw (java.io.StringWriter.)
        qc-set (binding [*err* sw]
                 (emit/warn-if-qc-detected! facts
                                            {:qc-emit-installed? true}))]
    (testing "QC still detected"
      (is (= #{:emp/sophie} qc-set)))
    (testing "But no warning printed"
      (is (empty? (str sw))))))

;; ============================================================================
;; QC RL-1 end-to-end — TPZ-1015 totals + RL-1 submission build (ADR-087)
;; ============================================================================

(deftest qc-rl1-end-to-end
  ;; Bootstrap a CA inc with Sophie (QC) + James (ON), run a payroll
  ;; cycle posting both, then verify:
  ;;   (1) TPZ-1015 helper sums the four RQ buckets correctly
  ;;   (2) The QC emit-provider produces FR audit-doc rows
  ;;   (3) build-rl1-submission! generates a valid XML envelope
  ;;       containing only Sophie's slip + a Sommaire1.
  (let [conn (bootstrap)
        db (d/db conn)
        cad (d/q '[:find ?e . :where [?e :kontor.commodity/symbol "CAD"]] db)
        ent (d/q '[:find ?e . :where [?e :kontor.entity/code "ACME-CA"]] db)
        journal (d/q '[:find ?e . :where [?e :kontor.journal/code "PAY-CA"]] db)
        period (d/q '[:find ?e . :where [?e :period/name "2026-05"]] db)
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
           conn {:code "ACME-2026-05-QC" :entity ent
                 :start-date #inst "2026-05-01"
                 :end-date #inst "2026-05-31"
                 :frequency :monthly
                 :fiscal-period period})
        pp-eid (hr/pay-period-by-code (d/db conn) "ACME-2026-05-QC")
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
                  :ca-payroll-qc-itx             (get-account-eid db "2511")
                  :ca-payroll-fss                (get-account-eid db "2532")
                  :ca-payroll-er-fss             (get-account-eid db "5417")}
        compute-provider (->MockCaCompute
                          {:per-emp {james-emp {:province "ON"}
                                     sophie-emp {:province "QC"}}})
        posting-builder (pb/->CaPayrollPostingBuilder
                         {:commodity cad :rp-account-tag nil})
        ;; Pass :qc-emit-installed? so the warning is suppressed
        emit-provider (emit/->CaPayrollEmitProvider
                       {:language :en :qc-emit-installed? true})
        sw (java.io.StringWriter.)
        report (binding [*err* sw]
                 (payroll/run-payroll!
                  conn {:pay-period pp-eid
                        :entity ent
                        :employments [james-emp sophie-emp]
                        :compute-provider compute-provider
                        :posting-builder posting-builder
                        :emit-provider emit-provider
                        :accounts accounts
                        :run-code "ACME-2026-05-QC-001"
                        :tx-code "TX-ACME-2026-05-QC"
                        :journal journal
                        :commodity cad}))]
    (testing "Payroll run completed without QC warning (emitter installed)"
      (is (not (re-find #"QC employments detected" (str sw)))))
    (testing "TPZ-1015 helper sums the four Revenu Québec buckets"
      (let [summary (tpz1015/tpz1015-period-due
                     conn {:period-start #inst "2026-05-01"
                           :period-end #inst "2026-06-01"
                           :remitter-type :monthly})]
        ;; Sophie's QC-ITX = 260
        (is (= 260M (:amount (:qc-itx summary))))
        ;; QPP = Sophie employee 353 + employer 353 = 706
        (is (= 706M (:amount (:qpp summary))))
        ;; QPIP = Sophie employee 53 + employer 75 = 128
        (is (= 128M (:amount (:qpip summary))))
        ;; FSS = employer 215
        (is (= 215M (:amount (:fss summary))))
        ;; Total = 1309
        (is (= 1309M (:amount (:total summary))))
        (is (some? (:due-date summary)))))
    (testing "QC emit-provider emits FR audit-doc row when invoked"
      ;; Inline call — not threaded through run-payroll! since
      ;; PayrollEmitProvider only takes one emit-provider slot today.
      ;; The wiring shape under ADR-087 is consumer-composed.
      (let [qc-prov (qc-emit/->QcPayrollEmitProvider {:language :fr})
            facts (ppro/compute-payroll
                   compute-provider {:employment-eids [sophie-emp]})
            events (ppro/emit-payroll-events
                    qc-prov facts {:pay-period-eid pp-eid :entity-eid ent})]
        (is (= 1 (count events)))
        (is (= :payroll-filing (:audit-doc/category (first events))))
        (is (= :fr (:audit-doc/language (first events))))))
    (testing "build-rl1-submission! generates valid XML envelope"
      (let [;; Build a synthetic full-year facts list (12 × monthly) so
            ;; the RL-1 + Sommaire1 totals make sense.
            year-facts (vec
                        (concat
                         (mapcat (fn [_]
                                   (ppro/compute-payroll
                                    compute-provider
                                    {:employment-eids [sophie-emp james-emp]}))
                                 (range 12))))
            persons-by-emp {sophie-emp {:given-name "Sophie"
                                        :family-name "Lavoie"
                                        :national-id-sin "123456782"
                                        :address {:line-1 "100 rue Sainte-Catherine"
                                                  :city "Montréal" :province "QC"
                                                  :country "CAN" :postal-code "H2X1A1"}}
                            james-emp {:given-name "James"
                                       :family-name "MacDonald"
                                       :national-id-sin "123456790"}}
            result (qc-emit/build-rl1-submission!
                    {:db (d/db conn)
                     :facts year-facts
                     :employer-neq "1234567890"
                     :employer-id-number "NP000001"
                     :employer-name "Acme Canada Inc."
                     :tax-year 2026
                     :transmitter {:transmetteur/np-number "NP000001"
                                   :transmetteur/neq "1234567890"
                                   :transmetteur/name "Acme Canada Inc."
                                   :transmetteur/contact
                                   {:name "A. Payroll"
                                    :phone "514-555-0100"
                                    :email "payroll@acme.ca"}}
                     :persons-by-emp persons-by-emp
                     :fss-contribution (money/money 2580M :CAD)})
            xml-str (xml/emit-str (:submission result))]
        (is (= 1 (count (:slips result))) "Only Sophie (QC) gets a slip")
        (is (re-find #"<NAS>123456782</NAS>" xml-str))
        (is (not (re-find #"<NAS>123456790</NAS>" xml-str)) "James (ON) excluded")
        (is (re-find #"<Sommaire1>" xml-str))
        (is (re-find #"<Case30>2580\.00</Case30>" xml-str) "FSS present in summary")
        (is (= :fr (:audit-doc/language (first (:audit-doc-tx-data result)))))))))

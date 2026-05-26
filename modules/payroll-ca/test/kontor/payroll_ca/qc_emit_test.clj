(ns kontor.payroll-ca.qc-emit-test
  "Tests for the QcPayrollEmitProvider + build-rl1-submission!.
   Per ADR-087."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.money :as money]
            [kontor.payroll-ca.emit :as emit]
            [kontor.payroll-ca.qc-emit :as qc-emit]
            [kontor.payroll-provider :as pp]))

(defn- qc-fact [emp-eid]
  {:employment emp-eid
   :gross 5500M :net 4192M
   :components [{:kind :base-wage          :amount 5500M  :employer-side? false}
                {:kind :employee-qc-itx    :amount -260M  :employer-side? false}
                {:kind :employee-qpp       :amount -353M  :employer-side? false}
                {:kind :employee-qpip      :amount -53M   :employer-side? false}
                {:kind :employee-ei        :amount -82M   :employer-side? false}]
   :jurisdiction-specific-codes
   {:province-of-employment "QC"
    :qpip-insurable-earnings 5500M}})

(defn- on-fact [emp-eid]
  {:employment emp-eid
   :gross 7000M :net 5400M
   :components [{:kind :base-wage          :amount 7000M    :employer-side? false}
                {:kind :income-tax-withheld :amount -1000M  :employer-side? false}
                {:kind :employee-cpp       :amount -400M    :employer-side? false}
                {:kind :employee-ei        :amount -115M    :employer-side? false}]
   :jurisdiction-specific-codes
   {:province-of-employment "ON"}})

;; ============================================================================
;; QcPayrollEmitProvider per-pay-period audit-doc
;; ============================================================================

(deftest qc-emit-emits-fr-audit-doc-when-qc-detected
  (let [provider (qc-emit/->QcPayrollEmitProvider {})
        events (pp/emit-payroll-events
                provider
                [(qc-fact :emp/sophie) (qc-fact :emp/jean)]
                {:pay-period-eid 42 :entity-eid 7})]
    (testing "One QC audit-doc row emitted"
      (is (= 1 (count events))))
    (testing "Category is :payroll-filing"
      (is (= :payroll-filing (:kontor.audit-doc/category (first events)))))
    (testing "Language is :fr (RL-1 convention)"
      (is (= :fr (:kontor.audit-doc/language (first events)))))
    (testing "Title mentions QC + employment count"
      (is (re-find #"QC payroll run" (:kontor.audit-doc/title (first events)))))))

(deftest qc-emit-noop-when-no-qc-employees
  (let [provider (qc-emit/->QcPayrollEmitProvider {})
        events (pp/emit-payroll-events
                provider
                [(on-fact :emp/james)]
                {:pay-period-eid 42 :entity-eid 7})]
    (testing "No audit-doc emitted when all employees are non-QC"
      (is (empty? events)))))

(deftest qc-emit-language-override
  (let [provider (qc-emit/->QcPayrollEmitProvider {:language :en})
        events (pp/emit-payroll-events
                provider
                [(qc-fact :emp/sophie)]
                {:pay-period-eid 1 :entity-eid 1})]
    (testing "Language override honored"
      (is (= :en (:kontor.audit-doc/language (first events)))))))

;; ============================================================================
;; warn-if-qc-detected! — suppression when emitter installed
;; ============================================================================

(deftest warn-suppressed-when-qc-emitter-installed
  (let [facts [(qc-fact :emp/sophie)]
        warn-output (java.io.StringWriter.)]
    (binding [*err* warn-output]
      (emit/warn-if-qc-detected! facts {:qc-emit-installed? true}))
    (testing "No WARN output when emitter is installed"
      (is (empty? (str warn-output))))))

(deftest warn-fires-when-qc-emitter-NOT-installed
  (let [facts [(qc-fact :emp/sophie)]
        warn-output (java.io.StringWriter.)]
    (binding [*err* warn-output]
      (emit/warn-if-qc-detected! facts {:qc-emit-installed? false}))
    (testing "WARN output present when emitter is missing"
      (is (re-find #"QC employments detected" (str warn-output))))
    (testing "WARN references ADR-087"
      (is (re-find #"ADR-087" (str warn-output))))))

(deftest warn-fires-by-default-on-single-arity
  (let [facts [(qc-fact :emp/sophie)]
        warn-output (java.io.StringWriter.)]
    (binding [*err* warn-output]
      (emit/warn-if-qc-detected! facts))
    (testing "Default single-arity behaves as :qc-emit-installed? false"
      (is (re-find #"QC employments detected" (str warn-output))))))

;; ============================================================================
;; build-rl1-submission! — end-to-end
;; ============================================================================

(deftest build-rl1-submission-end-to-end
  (let [conn (core/create-test-db)
        db (d/db conn)
        sophie-facts (vec (repeat 12 (qc-fact :emp/sophie)))
        ;; Mix in a non-QC fact to verify filtering
        on-facts (vec (repeat 12 (on-fact :emp/james)))
        facts (concat sophie-facts on-facts)
        persons-by-emp {:emp/sophie {:given-name "Sophie" :family-name "Lavoie"
                                     :national-id-sin "123456782"
                                     :address {:line-1 "1 rue Saint"
                                               :city "Montréal" :province "QC"
                                               :country "CAN" :postal-code "H2X1A1"}}
                        :emp/james {:given-name "James" :family-name "MacDonald"
                                    :national-id-sin "123456790"}}
        result (qc-emit/build-rl1-submission!
                {:db db
                 :facts facts
                 :employer-neq "1234567890"
                 :employer-id-number "NP123456"
                 :employer-name "Acme Québec Inc."
                 :tax-year 2026
                 :transmitter {:transmetteur/np-number "NP000001"
                               :transmetteur/neq "1234567890"
                               :transmetteur/name "Acme Québec Inc."
                               :transmetteur/contact
                               {:name "P. Payroll" :phone "514-555-0100"
                                :email "payroll@acme.qc.ca"}}
                 :persons-by-emp persons-by-emp
                 :fss-contribution (money/money 4500M :CAD)
                 :slip-reference-numbers {:emp/sophie "RL1-2026-000001"}})
        xml-str (clojure.data.xml/emit-str (:submission result))]
    (testing "Only QC employee (Sophie) gets an RL-1 slip — ON filtered"
      (is (= 1 (count (:slips result))))
      (is (re-find #"<NAS>123456782</NAS>" xml-str))
      (is (not (re-find #"<NAS>123456790</NAS>" xml-str))))
    (testing "Slip reference number was threaded through"
      (is (re-find #"<NumeroReleve>RL1-2026-000001</NumeroReleve>" xml-str)))
    (testing "Sommaire1 appears in envelope"
      (is (re-find #"<Sommaire1>" xml-str)))
    (testing "Sommaire1 Box 21 = Sophie QC-ITX × 12 = 3120"
      (is (re-find #"<Case21>3120\.00</Case21>" xml-str)))
    (testing "Sommaire1 Box 30 = FSS = 4500"
      (is (re-find #"<Case30>4500\.00</Case30>" xml-str)))
    (testing "Audit-doc tx-data is :fr + :payroll-filing"
      (let [doc (first (:audit-doc-tx-data result))]
        (is (= :payroll-filing (:kontor.audit-doc/category doc)))
        (is (= :fr (:kontor.audit-doc/language doc)))
        (is (re-find #"1234567890" (:kontor.audit-doc/title doc)))))))

(deftest build-rl1-submission-requires-keys
  (testing "Missing :employer-neq throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #":employer-neq"
                          (qc-emit/build-rl1-submission!
                           {:db nil :facts [] :tax-year 2026
                            :employer-name "Acme"
                            :transmitter {} :persons-by-emp {}}))))
  (testing "Missing :persons-by-emp throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #":persons-by-emp"
                          (qc-emit/build-rl1-submission!
                           {:db nil :facts [] :employer-neq "1234567890"
                            :employer-name "Acme" :tax-year 2026
                            :transmitter {}})))))

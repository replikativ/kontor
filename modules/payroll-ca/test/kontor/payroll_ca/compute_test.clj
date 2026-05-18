(ns kontor.payroll-ca.compute-test
  "Tests for Ceridian + ADP-CA CSV parsers + the Wagepoint skeleton."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [kontor.payroll-ca.compute :as compute]
            [kontor.payroll-provider :as pp]))

(def ceridian-fixture
  (io/resource "kontor/payroll_ca/fixtures/ceridian_sample.csv"))

(def adp-fixture
  (io/resource "kontor/payroll_ca/fixtures/adp_sample.csv"))

(def ceridian-codes
  "Map from Ceridian engine pay-element codes to kontor component
   kinds. The :employer-side? variants flag employer accruals; the
   `-PAY` codes mirror the employer expense onto the payable bucket
   (Ceridian's typical convention)."
  {"REG"        :base-wage
   "FED-TAX"    :income-tax-withheld
   "CPP-EE"     :employee-cpp
   "EI-EE"      :employee-ei
   ;; The -ER expense and -ER-PAY payable both come in as engine rows;
   ;; we map the expense one to :employer-cpp (employer-side? auto)
   ;; and SKIP the -PAY because the posting builder will derive the
   ;; payable side. To avoid double-counting, map -ER-PAY to a NULL
   ;; sentinel kind that we recognize and ignore (or use the wages
   ;; tag with employer-side? false to neutralize).
   "CPP-ER"     :employer-cpp
   "CPP-ER-PAY" {:kind :__skip-payable :employer-side? false}
   "EI-ER"      :employer-ei
   "EI-ER-PAY"  {:kind :__skip-payable :employer-side? false}})

(def adp-codes
  ;; ADP RUN canonical pay-element codes for CA (note 84 §2.3 +
  ;; engine-published reference). Same kontor-side mapping as
  ;; Ceridian (codes are different; semantics are the same).
  {"REG"        :base-wage
   "FED-TAX"    :income-tax-withheld
   "CPP-EE"     :employee-cpp
   "EI-EE"      :employee-ei
   "CPP-ER"     :employer-cpp
   "CPP-ER-PAY" {:kind :__skip-payable :employer-side? false}
   "EI-ER"      :employer-ei
   "EI-ER-PAY"  {:kind :__skip-payable :employer-side? false}
   "NET-WAGES"  {:kind :__skip-payable :employer-side? false}})

;; ============================================================================
;; Ceridian CSV parser
;; ============================================================================

(deftest ceridian-csv-parses-and-groups-by-employee
  (let [parsed (compute/parse-ceridian-csv
                ceridian-fixture
                {:pay-element-codes ceridian-codes})]
    (testing "Parsed every non-balancer-mirror row"
      ;; Fixture has 12 data rows; 2 are -PAY mirrors that get dropped.
      (is (= 10 (count parsed))))
    (testing "Each row carries the four required keys"
      (is (every? #(every? (fn [k] (contains? % k))
                           [:employee-external-id :kind :amount :employer-side?])
                  parsed)))
    (testing "Debit becomes positive amount; credit becomes negative"
      (let [jane-reg (->> parsed
                          (filter (fn [r]
                                    (and (= "E-jane" (:employee-external-id r))
                                         (= :base-wage (:kind r)))))
                          first)
            jane-fedtax (->> parsed
                             (filter (fn [r]
                                       (and (= "E-jane" (:employee-external-id r))
                                            (= :income-tax-withheld (:kind r)))))
                             first)]
        (is (= 5000M (:amount jane-reg)))
        (is (= -850M (:amount jane-fedtax)))))
    (testing "Employer-side flag is set for engine codes that map to employer kinds"
      (let [employer-rows (filter :employer-side? parsed)]
        (is (every? #(#{:employer-cpp :employer-ei} (:kind %)) employer-rows))))))

(deftest ceridian-facts-build-from-parsed-rows
  (let [parsed (compute/parse-ceridian-csv
                ceridian-fixture
                {:pay-element-codes ceridian-codes})
        facts (compute/ceridian-facts
               parsed
               {:external-id->eid {"E-jane" 100 "E-james" 200}
                :pay-period-eid 1
                :commodity-eid 999})]
    (testing "One PayrollFact per distinct employee"
      (is (= 2 (count facts))))
    (testing "Gross / net derived from the components"
      (let [jane (first (filter #(= 100 (:employment %)) facts))]
        (is (= 5000M (:gross jane)))
        ;; 5000 − 850 (fed tax) − 260.30 (CPP) − 81.50 (EI) = 3808.20
        (is (= 3808.20M (:net jane)))))
    (testing "Employer-side components carried but don't affect gross/net"
      (let [jane (first (filter #(= 100 (:employment %)) facts))
            employer-comps (filter :employer-side? (:components jane))]
        (is (= 2 (count employer-comps)))
        (is (= #{:employer-cpp :employer-ei}
               (set (map :kind employer-comps))))))))

(deftest ceridian-unknown-pay-element-throws
  (let [bad-codes (dissoc ceridian-codes "FED-TAX")]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown pay-element"
                          (compute/parse-ceridian-csv
                           ceridian-fixture
                           {:pay-element-codes bad-codes})))))

;; ============================================================================
;; ADP CSV parser (CA mode)
;; ============================================================================

(deftest adp-csv-parses-net-zero
  (let [parsed (compute/parse-adp-csv
                adp-fixture
                {:pay-element-codes adp-codes})]
    (testing "Parsed pay-element rows; balancer + mirror rows excluded"
      ;; Fixture: 10 rows = 9 pay-element rows + 1 blank balancer.
      ;; Of the 9 pay-element rows, 3 map to :__skip-payable (NET-WAGES,
      ;; CPP-ER-PAY, EI-ER-PAY) and are dropped, leaving 6.
      (is (= 6 (count parsed))))
    (testing "Debit → positive, Credit → negative"
      (let [jane-reg (first (filter (fn [r] (and (= "E-jane" (:employee-external-id r))
                                                 (= :base-wage (:kind r))))
                                    parsed))]
        (is (= 5000M (:amount jane-reg)))))))

(deftest adp-imbalanced-csv-throws
  (let [imbal "client,gl,desc,date,amount,dci,emp,pe,cc,run
A1,5400,wages,2026-05-15,5000.00,D,E-jane,REG,HQ,R1
A1,2510,fedtax,2026-05-15,100.00,C,E-jane,FED-TAX,HQ,R1
"]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"per-employee sum != 0"
                          (compute/parse-adp-csv
                           (java.io.StringReader. imbal)
                           {:pay-element-codes adp-codes})))))

(deftest adp-facts-build-and-carry-jurisdiction-codes
  (let [parsed (compute/parse-adp-csv
                adp-fixture
                {:pay-element-codes adp-codes})
        facts (compute/adp-facts parsed
                                 {:external-id->eid {"E-jane" 42}
                                  :pay-period-eid 1
                                  :commodity-eid 999})]
    (testing "Jurisdiction codes carry the engine provenance"
      (is (= :adp-ca (-> facts first :jurisdiction-specific-codes :engine))))
    (testing "Net derived correctly"
      (is (= 3808.20M (:net (first facts)))))))

;; ============================================================================
;; Provider records (PayrollComputeProvider protocol)
;; ============================================================================

(deftest ceridian-provider-provider-id
  (is (= :ceridian-dayforce
         (pp/provider-id (compute/->CeridianDayforceGlProvider {})))))

(deftest adp-ca-provider-provider-id
  (is (= :adp-ca (pp/provider-id (compute/->AdpCanadaProvider {})))))

(deftest wagepoint-skeleton-throws-on-compute
  (let [provider (compute/->WagepointApiProvider {})]
    (testing "provider-id is :wagepoint-api"
      (is (= :wagepoint-api (pp/provider-id provider))))
    (testing "compute throws with a partner-program-gated message"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"partner-program-gated"
                            (pp/compute-payroll provider {}))))))

;; ============================================================================
;; End-to-end compute through the provider record
;; ============================================================================

(deftest ceridian-provider-end-to-end-compute
  (let [provider (compute/->CeridianDayforceGlProvider
                  {:csv-source ceridian-fixture
                   :pay-element-codes ceridian-codes
                   :external-id->eid {"E-jane" 100 "E-james" 200}
                   :commodity-eid 999})
        facts (pp/compute-payroll provider {:pay-period-eid 1})]
    (testing "Returns one fact per employee"
      ;; Both employees appear because both have rows.
      (is (= 2 (count facts))))
    (testing "Provider emits :ceridian-dayforce in jurisdiction codes"
      (is (every? #(= :ceridian-dayforce
                      (-> % :jurisdiction-specific-codes :engine))
                  facts)))))

(deftest adp-provider-end-to-end-compute
  (let [provider (compute/->AdpCanadaProvider
                  {:csv-source adp-fixture
                   :pay-element-codes adp-codes
                   :external-id->eid {"E-jane" 42}
                   :commodity-eid 999})
        facts (pp/compute-payroll provider {:pay-period-eid 1})]
    (testing "ADP provider returns facts"
      (is (= 1 (count facts))))
    (testing "Net correctly derived"
      (is (= 3808.20M (:net (first facts)))))))

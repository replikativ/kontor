(ns kontor.payroll-br.compute-test
  "Tests for RH Sistemas / Senior / Pluxee CSV parsers."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [kontor.payroll-br.compute :as compute]
            [kontor.payroll-provider :as pp]))

(def rh-fixture
  (io/resource "kontor/payroll_br/fixtures/rh_sistemas_sample.csv"))

(def pluxee-fixture
  (io/resource "kontor/payroll_br/fixtures/pluxee_sample.csv"))

(def rh-codes
  "Map from RH Sistemas rubrica codes to kontor component kinds."
  {"R001" :base-wage
   "R200" :inss-employee
   "R210" :irrf-employee
   "R900" :inss-employer
   "R901" :fgts-employer})

(def pluxee-codes
  ;; Pluxee uses similar rubrica codes; semantics identical.
  rh-codes)

;; ============================================================================
;; RH Sistemas CSV parser
;; ============================================================================

(deftest rh-csv-parses-and-groups-by-employee
  (let [parsed (compute/parse-rh-sistemas-csv
                rh-fixture
                {:rubrica-codes rh-codes})]
    (testing "Parsed every data row"
      (is (= 10 (count parsed))))
    (testing "Each row carries the four required keys"
      (is (every? #(every? (fn [k] (contains? % k))
                           [:employee-external-id :kind :amount :employer-side?])
                  parsed)))
    (testing "Provento becomes positive amount; desconto becomes negative"
      (let [jane-reg (->> parsed
                          (filter (fn [r]
                                    (and (= "11144477735" (:employee-external-id r))
                                         (= :base-wage (:kind r)))))
                          first)
            jane-inss (->> parsed
                           (filter (fn [r]
                                     (and (= "11144477735" (:employee-external-id r))
                                          (= :inss-employee (:kind r)))))
                           first)]
        (is (= 5000.00M (:amount jane-reg)))
        (is (= -400.00M (:amount jane-inss)))))
    (testing "Employer-side flag is set for engine codes that map to employer kinds"
      (let [employer-rows (filter :employer-side? parsed)]
        (is (every? #(#{:inss-employer :fgts-employer} (:kind %)) employer-rows))))))

(deftest rh-facts-build-from-parsed-rows
  (let [parsed (compute/parse-rh-sistemas-csv
                rh-fixture
                {:rubrica-codes rh-codes})
        facts (compute/rh-sistemas-facts
               parsed
               {:external-id->eid {"11144477735" 100
                                   "12345678909" 200}
                :pay-period-eid 1
                :commodity-eid 999})]
    (testing "One PayrollFact per distinct employee"
      (is (= 2 (count facts))))
    (testing "Gross / net derived from the components"
      (let [jane (first (filter #(= 100 (:employment %)) facts))]
        (is (= 5000.00M (:gross jane)))
        ;; 5000 - 400 INSS - 247.50 IRRF = 4352.50
        (is (= 4352.50M (:net jane)))))
    (testing "Employer-side components carried but don't affect gross/net"
      (let [jane (first (filter #(= 100 (:employment %)) facts))
            employer-comps (filter :employer-side? (:components jane))]
        (is (= 2 (count employer-comps)))
        (is (= #{:inss-employer :fgts-employer}
               (set (map :kind employer-comps))))))
    (testing "Rubrica annotation flows through to components"
      (let [jane (first (filter #(= 100 (:employment %)) facts))]
        (is (some #(= "R001" (:rubrica %)) (:components jane)))
        (is (some #(= "R200" (:rubrica %)) (:components jane)))))))

(deftest rh-unknown-rubrica-throws
  (let [bad-codes (dissoc rh-codes "R210")]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown BR rubrica"
                          (compute/parse-rh-sistemas-csv
                           rh-fixture
                           {:rubrica-codes bad-codes})))))

(deftest rh-coerce-bigdec-handles-br-locale
  ;; The br-locale handler converts '1.234,56' (BR format) to
  ;; '1234.56' (Java BigDecimal compatible).
  (let [csv-content "competencia,cpf,matricula,rubrica,provento,desconto
2026-05,11144477735,M-1001,R001,\"1.234,56\",0,00
"
        parsed (compute/parse-rh-sistemas-csv
                (java.io.StringReader. csv-content)
                {:rubrica-codes rh-codes})]
    (is (= 1 (count parsed)))
    (is (= 1234.56M (:amount (first parsed))))))

;; ============================================================================
;; Pluxee position-based CSV parser
;; ============================================================================

(deftest pluxee-csv-parses-and-builds-facts
  (let [parsed (compute/parse-pluxee-csv
                pluxee-fixture
                {:rubrica-codes pluxee-codes})]
    (testing "Parsed all rubrica rows"
      ;; 5 data rows in fixture, all valid
      (is (= 5 (count parsed))))
    (testing "BR-locale decimal comma parsed correctly"
      (let [reg (first (filter #(= :base-wage (:kind %)) parsed))]
        (is (= 5000.00M (:amount reg)))))))

(deftest pluxee-facts-carry-jurisdiction-codes
  (let [parsed (compute/parse-pluxee-csv
                pluxee-fixture
                {:rubrica-codes pluxee-codes})
        facts (compute/pluxee-facts
               parsed
               {:external-id->eid {"11144477735" 42}
                :pay-period-eid 1
                :commodity-eid 999})]
    (testing "Jurisdiction codes carry the engine provenance"
      (is (= :pluxee (-> facts first :jurisdiction-specific-codes :engine))))
    (testing "Net derived correctly: 5000 - 400 - 247.50 = 4352.50"
      (is (= 4352.50M (:net (first facts)))))))

;; ============================================================================
;; Provider records (PayrollComputeProvider protocol)
;; ============================================================================

(deftest rh-provider-provider-id
  (is (= :rh-sistemas
         (pp/provider-id (compute/->RhSistemasGlProvider {})))))

(deftest senior-provider-provider-id
  (is (= :senior-hcm (pp/provider-id (compute/->SeniorHcmGlProvider {})))))

(deftest pluxee-provider-provider-id
  (is (= :pluxee (pp/provider-id (compute/->PluxeeCsvGlProvider {})))))

(deftest provider-requires-csv-source
  (let [provider (compute/->RhSistemasGlProvider {:rubrica-codes rh-codes
                                                  :external-id->eid {}})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #":csv-source"
                          (pp/compute-payroll provider {})))))

(deftest provider-requires-rubrica-codes
  (let [provider (compute/->RhSistemasGlProvider
                  {:csv-source rh-fixture
                   :external-id->eid {"11144477735" 1}})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #":rubrica-codes"
                          (pp/compute-payroll provider {})))))

(deftest provider-requires-external-id-mapping
  (let [provider (compute/->RhSistemasGlProvider
                  {:csv-source rh-fixture
                   :rubrica-codes rh-codes})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #":external-id->eid"
                          (pp/compute-payroll provider {})))))

;; ============================================================================
;; End-to-end compute through the provider record
;; ============================================================================

(deftest rh-provider-end-to-end-compute
  (let [provider (compute/->RhSistemasGlProvider
                  {:csv-source rh-fixture
                   :rubrica-codes rh-codes
                   :external-id->eid {"11144477735" 100
                                      "12345678909" 200}
                   :commodity-eid 999})
        facts (pp/compute-payroll provider {:pay-period-eid 1})]
    (testing "Returns one fact per employee"
      (is (= 2 (count facts))))
    (testing "Provider emits :rh-sistemas in jurisdiction codes"
      (is (every? #(= :rh-sistemas
                      (-> % :jurisdiction-specific-codes :engine))
                  facts)))))

(deftest senior-provider-end-to-end-compute
  ;; Same CSV shape; only provider-id differs.
  (let [provider (compute/->SeniorHcmGlProvider
                  {:csv-source rh-fixture
                   :rubrica-codes rh-codes
                   :external-id->eid {"11144477735" 100
                                      "12345678909" 200}
                   :commodity-eid 999})
        facts (pp/compute-payroll provider {:pay-period-eid 1})]
    (testing "Provider emits :senior-hcm in jurisdiction codes"
      (is (every? #(= :senior-hcm
                      (-> % :jurisdiction-specific-codes :engine))
                  facts)))))

(deftest pluxee-provider-end-to-end-compute
  (let [provider (compute/->PluxeeCsvGlProvider
                  {:csv-source pluxee-fixture
                   :rubrica-codes pluxee-codes
                   :external-id->eid {"11144477735" 42}
                   :commodity-eid 999})
        facts (pp/compute-payroll provider {:pay-period-eid 1})]
    (testing "Provider returns one fact for the single employee"
      (is (= 1 (count facts))))
    (testing "Net derived correctly"
      (is (= 4352.50M (:net (first facts)))))))

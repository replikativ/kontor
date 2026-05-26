(ns kontor.payroll-fr.emit-test
  "Tests for the FR DSN emit-provider + termination helper."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kontor.payroll-fr.emit :as emit]
            [kontor.payroll-provider :as pp]))

;; ============================================================================
;; FrDsnEmitProvider — without envelope (minimal mode)
;; ============================================================================

(deftest emit-provider-minimal-mode
  (testing "FrDsnEmitProvider with no envelope returns one skeleton audit-doc"
    (let [provider (emit/->FrDsnEmitProvider {:language :fr})
          facts [{:employment 101 :gross 3700M :net 2566.36M
                  :components [{:kind :base-salary :amount 3700M :employer-side? false}]}]
          docs (pp/emit-payroll-events provider facts
                                       {:pay-period-eid 999 :entity-eid 7})]
      (is (= 1 (count docs)))
      (let [doc (first docs)]
        (is (= :payroll-filing (:kontor.audit-doc/category doc)))
        (is (= :fr (:kontor.audit-doc/language doc)))
        (is (= :regulator-clearance (:kontor.audit-doc/type doc)))
        (is (str/starts-with? (:kontor.audit-doc/code doc) "DSN-"))
        (is (str/includes? (:kontor.audit-doc/description doc) "skeleton"))))))

(deftest emit-provider-full-payload-mode
  (testing "FrDsnEmitProvider with envelope/entreprise/établissement supplied"
    (let [provider
          (emit/->FrDsnEmitProvider
           {:language :fr
            :envelope {:siren "123456782"
                       :nom-emetteur "Acme France SAS"
                       :adresse-emetteur "10 rue de la Paix"
                       :telephone-emetteur "+33145000000"
                       :email-emetteur "paie@acme.fr"
                       :code-organisme "URSSAF-IDF-110"
                       :date-creation #inst "2026-06-05"
                       :nature :reel :type-envoi :normal}
            :entreprise {:siren "123456782" :ape "6201Z"}
            :etablissement {:siret "12345678900012" :code-ape "6201Z"
                            :adresse "10 rue de la Paix" :code-postal "75001"
                            :ville "Paris"}
            :persons-by-emp {101 {:nir "180056789012345"
                                  :nom-de-famille "Dupont" :prenom "Jean"
                                  :date-de-naissance #inst "1980-05-15"
                                  :sexe :h :lieu-de-naissance "75056"
                                  :adresse "10 rue de Rivoli"
                                  :code-postal "75004" :ville "Paris"}}
            :pay-period-start #inst "2026-05-01"
            :pay-period-end #inst "2026-05-31"
            :date-versement #inst "2026-05-31"
            :type-pas :perso})
          facts [{:employment 101 :gross 3700M :net 2566.36M
                  :components [{:kind :base-salary :amount 3500M :employer-side? false}
                               {:kind :overtime :amount 200M :employer-side? false}
                               {:kind :cotisation-urssaf :amount -284.80M :employer-side? false}
                               {:kind :pas-withholding :amount -284M :employer-side? false}]
                  :jurisdiction-specific-codes {:taux-pas 0.075M}}]
          docs (pp/emit-payroll-events provider facts
                                       {:pay-period-eid 999 :entity-eid 7})]
      (is (= 1 (count docs)))
      (let [doc (first docs)
            desc (:kontor.audit-doc/description doc)]
        (is (= :payroll-filing (:kontor.audit-doc/category doc)))
        (is (= :fr (:kontor.audit-doc/language doc)))
        ;; The description is the serialized NEODES payload
        (is (str/includes? desc "S10.G00.00,001,'123456782'"))
        (is (str/includes? desc "S21.G00.51"))
        (is (str/includes? desc "S21.G00.50,002,'2566.36'"))))))

;; ============================================================================
;; build-dsn-audit-doc-tx-data
;; ============================================================================

(deftest build-audit-doc-required-keys
  (testing "Missing :siret throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #":siret required"
                          (emit/build-dsn-audit-doc-tx-data
                           {:pay-period "2026-05" :individus-count 3}))))
  (testing "Missing :pay-period throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #":pay-period required"
                          (emit/build-dsn-audit-doc-tx-data
                           {:siret "12345678900012"})))))

(deftest build-audit-doc-shape
  (testing "Audit-doc carries the canonical category + language"
    (let [[doc] (emit/build-dsn-audit-doc-tx-data
                 {:siret "12345678900012"
                  :pay-period "2026-05"
                  :individus-count 3
                  :language :fr})]
      (is (= :payroll-filing (:kontor.audit-doc/category doc)))
      (is (= :fr (:kontor.audit-doc/language doc)))
      (is (= "DSN-12345678900012-2026-05" (:kontor.audit-doc/code doc)))
      (is (str/includes? (:kontor.audit-doc/title doc) "SIRET 12345678900012"))
      (is (str/includes? (:kontor.audit-doc/title doc) "2026-05"))
      (is (str/includes? (:kontor.audit-doc/title doc) "3 individus"))))
  (testing "Nature + type-envoi reflected in title"
    (let [[doc] (emit/build-dsn-audit-doc-tx-data
                 {:siret "1" :pay-period "2026-05" :individus-count 0
                  :language :fr :nature :test :type-envoi :neant})]
      (is (str/includes? (:kontor.audit-doc/title doc) "test"))
      (is (str/includes? (:kontor.audit-doc/title doc) "néant"))))
  (testing "Custom code overrides the default"
    (let [[doc] (emit/build-dsn-audit-doc-tx-data
                 {:siret "1" :pay-period "2026-05" :individus-count 1
                  :code "CUSTOM-CODE-XYZ"})]
      (is (= "CUSTOM-CODE-XYZ" (:kontor.audit-doc/code doc)))))
  (testing "Storage URI flows into :kontor.audit-doc/storage-uri"
    (let [[doc] (emit/build-dsn-audit-doc-tx-data
                 {:siret "1" :pay-period "2026-05" :individus-count 1
                  :submitted-uri "s3://kontor-dsn/2026-05/dsn-payload.txt"})]
      (is (= "s3://kontor-dsn/2026-05/dsn-payload.txt"
             (:kontor.audit-doc/storage-uri doc))))))

;; ============================================================================
;; terminate-employment-tx-data
;; ============================================================================

(deftest terminate-employment-shape
  (testing "Termination produces an audit-doc + an :employment update"
    (let [[doc emp] (emit/terminate-employment-tx-data
                     nil
                     {:employment-eid 101
                      :last-day-worked #inst "2026-05-31"
                      :termination-reason :demission})]
      (is (= :termination-event (:kontor.audit-doc/type doc)))
      (is (= :hr-personnel (:kontor.audit-doc/category doc)))
      (is (= :fr (:kontor.audit-doc/language doc)))
      (is (str/includes? (:kontor.audit-doc/description doc) "010"))  ; motif demission
      (is (= 101 (:db/id emp)))
      (is (= :terminated (:kontor.employment/state emp)))
      (is (= #inst "2026-05-31" (:kontor.employment/end-date emp)))
      (is (= :demission (:kontor.employment/termination-reason emp)))))
  (testing "Final-pay-period-end-date when supplied flows into :employment"
    (let [[_ emp] (emit/terminate-employment-tx-data
                   nil
                   {:employment-eid 101
                    :last-day-worked #inst "2026-05-31"
                    :final-pay-period-end-date #inst "2026-06-15"
                    :termination-reason :rupture-conventionnelle})]
      (is (= #inst "2026-06-15" (:kontor.employment/final-pay-period-end-date emp)))))
  (testing "Unknown termination reason falls back to motif 999"
    (let [[doc _] (emit/terminate-employment-tx-data
                   nil
                   {:employment-eid 101
                    :last-day-worked #inst "2026-05-31"
                    :termination-reason :consumer-bespoke-reason})]
      (is (str/includes? (:kontor.audit-doc/description doc) "999")))))

(deftest terminate-employment-required-args
  (testing "Missing employment-eid throws"
    (is (thrown? clojure.lang.ExceptionInfo
                 (emit/terminate-employment-tx-data
                  nil
                  {:last-day-worked #inst "2026-05-31"
                   :termination-reason :demission}))))
  (testing "Missing last-day-worked throws"
    (is (thrown? clojure.lang.ExceptionInfo
                 (emit/terminate-employment-tx-data
                  nil
                  {:employment-eid 101
                   :termination-reason :demission}))))
  (testing "Missing termination-reason throws"
    (is (thrown? clojure.lang.ExceptionInfo
                 (emit/terminate-employment-tx-data
                  nil
                  {:employment-eid 101
                   :last-day-worked #inst "2026-05-31"})))))

;; ============================================================================
;; Misc helpers
;; ============================================================================

(deftest dsn-month-from-period
  (testing "dsn-month-from-period extracts YYYY-MM"
    (is (= "2026-05" (emit/dsn-month-from-period #inst "2026-05-01")))
    (is (= "2026-12" (emit/dsn-month-from-period #inst "2026-12-15")))))

(deftest validate-period-code
  (testing "Period code validator accepts YYYY-MM"
    (is (true? (emit/validate-period-code "2026-05")))
    (is (true? (emit/validate-period-code "2026-12")))
    (is (false? (emit/validate-period-code "2026-5")))
    (is (false? (emit/validate-period-code "26-05")))
    (is (false? (emit/validate-period-code "not a date")))
    (is (false? (emit/validate-period-code nil)))))

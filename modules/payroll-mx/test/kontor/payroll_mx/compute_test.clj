(ns kontor.payroll-mx.compute-test
  "Tests for CONTPAQi + Aspel CSV compute providers."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [kontor.payroll-mx.compute :as compute]
            [kontor.payroll-mx.core :as core]))

(deftest contpaqi-provider-parses-the-fixture
  (let [provider (compute/make-contpaqi-nominas-provider)
        facts (core/parse-period
               provider
               (io/resource "kontor/payroll_mx/fixtures/contpaqi-sample.csv"))]
    (is (= :contpaqi-nominas (core/vendor-id provider)))
    (is (= 2 (count facts))
        "Two distinct (RFC, period) records produce two :payroll-facts")
    (let [emp1 (first (filter #(= "ABCD800101AB1" (:employee/rfc %)) facts))
          emp2 (first (filter #(= "EFGH900202EF2" (:employee/rfc %)) facts))]
      (is (some? emp1))
      (is (some? emp2))
      (testing "Employee 1 has all 6 wage rows from the fixture"
        (is (= 6 (count (:wage-types emp1))))
        (is (= #{:sueldo :hora-extra-doble :isr-retencion :imss-trabajador
                 :subsidio-al-empleo :imss-patron}
               (set (mapv :wage-type (:wage-types emp1))))))
      (testing "Amounts are BigDecimal HALF-EVEN at scale 2"
        (let [sueldo (first (filter #(= :sueldo (:wage-type %))
                                    (:wage-types emp1)))]
          (is (instance? java.math.BigDecimal (:amount sueldo)))
          (is (= 2 (.scale ^java.math.BigDecimal (:amount sueldo))))
          (is (= 7500.00M (:amount sueldo))))))))

(deftest aspel-provider-parses-the-fixture
  (let [provider (compute/make-aspel-noi-provider)
        facts (core/parse-period
               provider
               (io/resource "kontor/payroll_mx/fixtures/aspel-sample.csv"))]
    (is (= :aspel-noi (core/vendor-id provider)))
    (is (= 1 (count facts)))
    (let [facts (first facts)]
      (is (= "ABCD800101AB1" (:employee/rfc facts)))
      (is (= #{:sueldo :isr-retencion :imss-trabajador :imss-patron}
             (set (mapv :wage-type (:wage-types facts))))))))

(deftest unmapped-vendor-codes-are-silently-skipped
  (testing "A vendor code missing from the code-map is filtered out
            (we do NOT raise — vendor files routinely contain bookkeeping
            rows we don't need to GL)."
    (let [provider (compute/make-contpaqi-nominas-provider
                    {:code-map {} :replace-map true})
          facts (core/parse-period
                 provider
                 (io/resource "kontor/payroll_mx/fixtures/contpaqi-sample.csv"))]
      (is (empty? facts)
          "Empty code-map → every row is skipped, no facts produced"))))

(deftest custom-code-map-overrides-default
  (let [provider (compute/make-contpaqi-nominas-provider
                  {:code-map {"P001" :aguinaldo}})   ; pretend P001=aguinaldo
        facts (core/parse-period
               provider
               (io/resource "kontor/payroll_mx/fixtures/contpaqi-sample.csv"))]
    (is (every? #(contains? (set (mapv :wage-type (:wage-types %)))
                            :aguinaldo) facts))))

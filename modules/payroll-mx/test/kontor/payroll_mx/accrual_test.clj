(ns kontor.payroll-mx.accrual-test
  "Tests for aguinaldo + prima vacacional accrual primitives."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.payroll-mx.accrual :as accrual]
            [kontor.validation :as validation]))

(deftest aguinaldo-default-is-15-days-divided-by-12
  (testing "LFT Art. 87 — 15 days / 12 = 1.25 days per month."
    (is (= 125.00M (accrual/aguinaldo-monthly-accrual 100.00M)))))

(deftest aguinaldo-custom-bonus-days
  (testing "Common 30-day aguinaldo → 2.5 days/month."
    (is (= 250.00M (accrual/aguinaldo-monthly-accrual 100.00M 30)))))

(deftest aguinaldo-rounds-half-even
  (testing "Awkward daily salary → HALF-EVEN at scale 2."
    ;; 333.33 × 15 / 12 = 416.6625 → 416.66 (HALF-EVEN)
    (is (= 416.66M (accrual/aguinaldo-monthly-accrual 333.33M)))))

(deftest aguinaldo-requires-daily-salary
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #":daily-salary required"
                        (accrual/aguinaldo-monthly-accrual nil))))

(deftest vacation-days-by-tenure-follows-2023-lft-reform
  (is (= 12 (accrual/vacation-days 1))
      "Year 1 = 12 days (post-2023 Vacaciones Dignas)")
  (is (= 14 (accrual/vacation-days 2)))
  (is (= 16 (accrual/vacation-days 3)))
  (is (= 18 (accrual/vacation-days 4)))
  (is (= 20 (accrual/vacation-days 5)))
  (is (= 22 (accrual/vacation-days 6))
      "Year 6 starts a new 5-year bucket → +2 → 22")
  (is (= 22 (accrual/vacation-days 10)))
  (is (= 24 (accrual/vacation-days 11))))

(deftest prima-vacacional-is-25-percent-on-top
  ;; 500/day × 12 days = 6000 vacation pay → prima = 1500.
  (is (= 1500.00M (accrual/prima-vacacional 500.00M 12))))

(deftest prima-vacacional-custom-rate-supported
  (is (= 1800.00M (accrual/prima-vacacional 500.00M 12 0.30M))
      "Some collective contracts negotiate higher rates"))

;; ============================================================================
;; Posting builder — Dr 601.02 / Cr 206.07 provision
;; ============================================================================

(defn- bootstrap []
  (let [conn (core/create-test-db)]
    (d/transact conn
                [{:db/id "mxn" :kontor.commodity/symbol "MXN"
                  :kontor.commodity/name "Peso" :kontor.commodity/precision 2
                  :kontor.commodity/iso-4217 "MXN"}
                 {:kontor.account/code "601.02" :kontor.account/path "Gastos:Aguinaldo"
                  :kontor.account/name "Gratificación Anual"
                  :kontor.account/type :expense :kontor.account/active true}
                 {:kontor.account/code "206.07" :kontor.account/path "Pasivos:ProvisionAguinaldo"
                  :kontor.account/name "Provisión Aguinaldo"
                  :kontor.account/type :liability :kontor.account/active true}
                 {:journal/code "NOM" :journal/name "Nómina"
                  :journal/type :general :journal/active true}])
    conn))

(defn- mxn [db] (d/q '[:find ?e . :where [?e :kontor.commodity/symbol "MXN"]] db))
(defn- journal [db] (d/q '[:find ?e . :where [?e :journal/code "NOM"]] db))

(deftest aguinaldo-accrual-tx-is-balanced-and-transacts
  (let [conn (bootstrap)
        db (d/db conn)
        amount (accrual/aguinaldo-monthly-accrual 500.00M)
        tx (accrual/build-aguinaldo-accrual-tx-data
            {:db db
             :journal (journal db)
             :commodity (mxn db)
             :date #inst "2026-05-31"
             :amount amount})
        sum (reduce (fn [a r] (.add ^java.math.BigDecimal a
                                    ^java.math.BigDecimal (:posting/amount r)))
                    0M
                    (filter :posting/amount tx))]
    (is (zero? (.compareTo ^java.math.BigDecimal sum 0M)))
    (is (some? (validation/transact-with-validation conn tx)))))

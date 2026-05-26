(ns kontor.payroll-mx.posting-builder-test
  "Tests for the SAT Código Agrupador-keyed GL posting builder."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.payroll-mx.posting-builder :as pb]
            [kontor.validation :as validation]))

;; ============================================================================
;; Fixture — a minimal MX-style chart of accounts
;; ============================================================================

(defn- bootstrap []
  (let [conn (core/create-test-db)]
    (d/transact conn
                [{:db/id "mxn"
                  :kontor.commodity/symbol "MXN"
                  :kontor.commodity/name "Peso mexicano"
                  :kontor.commodity/precision 2
                  :kontor.commodity/iso-4217 "MXN"}
                 ;; SAT Código Agrupador chart — minimal
                 {:kontor.account/code "601.01" :kontor.account/path "Gastos:Sueldos"
                  :kontor.account/name "Sueldos y Salarios"
                  :kontor.account/type :expense :kontor.account/active true}
                 {:kontor.account/code "601.02" :kontor.account/path "Gastos:Aguinaldo"
                  :kontor.account/name "Gratificación Anual"
                  :kontor.account/type :expense :kontor.account/active true}
                 {:kontor.account/code "601.05" :kontor.account/path "Gastos:IMSS-Patron"
                  :kontor.account/name "Cuotas IMSS patronales"
                  :kontor.account/type :expense :kontor.account/active true}
                 {:kontor.account/code "601.06" :kontor.account/path "Gastos:INFONAVIT-Patron"
                  :kontor.account/name "Aportaciones INFONAVIT"
                  :kontor.account/type :expense :kontor.account/active true}
                 {:kontor.account/code "601.84" :kontor.account/path "Gastos:Prestaciones"
                  :kontor.account/name "Otras prestaciones"
                  :kontor.account/type :expense :kontor.account/active true}
                 {:kontor.account/code "206.01" :kontor.account/path "Pasivos:SueldosPorPagar"
                  :kontor.account/name "Sueldos por pagar"
                  :kontor.account/type :liability :kontor.account/active true}
                 {:kontor.account/code "206.04" :kontor.account/path "Pasivos:ISRPorPagar"
                  :kontor.account/name "Impuestos por pagar (ISR)"
                  :kontor.account/type :liability :kontor.account/active true}
                 {:kontor.account/code "206.05" :kontor.account/path "Pasivos:IMSSPorPagar"
                  :kontor.account/name "IMSS por pagar"
                  :kontor.account/type :liability :kontor.account/active true}
                 {:kontor.account/code "206.06" :kontor.account/path "Pasivos:INFONAVITPorPagar"
                  :kontor.account/name "INFONAVIT por pagar"
                  :kontor.account/type :liability :kontor.account/active true}
                 {:kontor.account/code "206.07" :kontor.account/path "Pasivos:ProvisionAguinaldo"
                  :kontor.account/name "Provisión Aguinaldo"
                  :kontor.account/type :liability :kontor.account/active true}
                 {:kontor.journal/code "NOM"
                  :kontor.journal/name "Nómina"
                  :kontor.journal/type :general
                  :kontor.journal/active true}])
    conn))

(defn- ref-eid [db a v]
  (d/q '[:find ?e . :in $ ?a ?v :where [?e ?a ?v]] db a v))

(defn- mxn [db] (ref-eid db :kontor.commodity/symbol "MXN"))
(defn- journal [db] (ref-eid db :kontor.journal/code "NOM"))
(defn- acct [db code] (ref-eid db :kontor.account/code code))

(def sample-facts
  "One semi-monthly period for two employees, mirroring the CONTPAQi
   fixture totals."
  [{:employee/rfc "ABCD800101AB1"
    :employee/curp "ABCD800101HDFRRR01"
    :employee/code "E001"
    :period/start #inst "2026-05-01"
    :period/end   #inst "2026-05-15"
    :period/payment-date #inst "2026-05-15"
    :payroll-facts/version 1
    :wage-types [{:wage-type :sueldo            :amount 7500.00M}
                 {:wage-type :hora-extra-doble  :amount 250.00M}
                 {:wage-type :isr-retencion     :amount 750.00M}
                 {:wage-type :imss-trabajador   :amount 150.00M}
                 {:wage-type :subsidio-al-empleo :amount 25.00M}
                 {:wage-type :imss-patron       :amount 400.00M}]}
   {:employee/rfc "EFGH900202EF2"
    :employee/curp "EFGH900202MDFRRR02"
    :employee/code "E002"
    :period/start #inst "2026-05-01"
    :period/end   #inst "2026-05-15"
    :period/payment-date #inst "2026-05-15"
    :payroll-facts/version 1
    :wage-types [{:wage-type :sueldo          :amount 6000.00M}
                 {:wage-type :isr-retencion   :amount 600.00M}
                 {:wage-type :imss-trabajador :amount 120.00M}
                 {:wage-type :imss-patron     :amount 320.00M}]}])

;; ============================================================================
;; Tests
;; ============================================================================

(deftest aggregate-by-codigo-sums-across-employees
  (let [aggs (pb/aggregate-by-codigo sample-facts)
        sueldo (get aggs ["601.01" :percepcion false :sueldo])
        hora-extra (get aggs ["601.01" :percepcion false :hora-extra-doble])
        imss-patron (get aggs ["601.05" :percepcion true :imss-patron])]
    (is (= 13500.00M sueldo)
        "Two employees × :sueldo amounts sum to 13500")
    (is (= 250.00M hora-extra))
    (is (= 720.00M imss-patron)
        "IMSS patrón across both employees")))

(deftest period-totals-roll-up-correctly
  (let [t (pb/period-totals sample-facts)]
    ;; worker-side percepciones = 7500 + 250 + 6000 = 13750
    (is (= 13750.00M (:total-percepciones t)))
    ;; deductions = 750 + 150 + 600 + 120 = 1620
    (is (= 1620.00M (:total-deducciones t)))
    ;; otros-pagos = 25 (subsidio)
    (is (= 25.00M (:total-otros-pagos t)))
    ;; employer-cost = 400 + 320 = 720 IMSS patrón
    (is (= 720.00M (:total-employer-cost t)))
    ;; neto = 13750 - 1620 + 25 = 12155
    (is (= 12155.00M (:neto-a-pagar t)))))

(deftest build-period-tx-data-is-balanced
  (let [conn (bootstrap)
        db (d/db conn)
        tx-data (pb/build-period-tx-data
                 {:db db
                  :journal (journal db)
                  :commodity (mxn db)
                  :period {:start #inst "2026-05-01"
                           :end   #inst "2026-05-15"
                           :payment-date #inst "2026-05-15"}
                  :facts sample-facts})
        ;; Sum :kontor.posting/amount across all posting rows. They must
        ;; cancel — that's `kontor.posting/build-transaction`'s
        ;; sum-to-zero invariant.
        posting-rows (filter :kontor.posting/amount tx-data)
        sum (reduce (fn [acc r] (.add ^java.math.BigDecimal acc
                                      ^java.math.BigDecimal (:kontor.posting/amount r)))
                    0M
                    posting-rows)]
    (is (zero? (.compareTo ^java.math.BigDecimal sum 0M))
        (str "Period journal must sum to zero — got " sum))))

(deftest build-period-tx-data-routes-the-right-codigos
  (let [conn (bootstrap)
        db (d/db conn)
        tx-data (pb/build-period-tx-data
                 {:db db
                  :journal (journal db)
                  :commodity (mxn db)
                  :period {:start #inst "2026-05-01"
                           :end   #inst "2026-05-15"
                           :payment-date #inst "2026-05-15"}
                  :facts sample-facts})
        rows (filter :kontor.posting/amount tx-data)
        by-acct (group-by :kontor.posting/account rows)
        sum-of (fn [code]
                 (->> (get by-acct (acct db code))
                      (map :kontor.posting/amount)
                      (reduce (fn [a b] (.add ^java.math.BigDecimal a
                                              ^java.math.BigDecimal b)) 0M)))]
    (testing "Dr 601.01 = 7500 + 250 + 6000 = 13750"
      (is (= 13750.00M (sum-of "601.01"))))
    (testing "Dr 601.05 = 400 + 320 = 720"
      (is (= 720.00M (sum-of "601.05"))))
    (testing "Cr 206.01 (neto) = -(13750 - 1620 + 25) = -12155"
      (is (= -12155.00M (sum-of "206.01"))))
    (testing "Cr 206.04 (ISR - subsidio) = -(750 + 600 - 25) = -1325"
      (is (= -1325.00M (sum-of "206.04"))))
    (testing "Cr 206.05 (IMSS = trab + patron) = -(270 + 720) = -990"
      (is (= -990.00M (sum-of "206.05"))))))

(deftest build-period-tx-data-transacts-cleanly
  (testing "End-to-end: the produced tx-data passes
            transact-with-validation (sum-to-zero + active-accounts
            + commodity-match)."
    (let [conn (bootstrap)
          db (d/db conn)
          tx-data (pb/build-period-tx-data
                   {:db db
                    :journal (journal db)
                    :commodity (mxn db)
                    :period {:start #inst "2026-05-01"
                             :end   #inst "2026-05-15"
                             :payment-date #inst "2026-05-15"}
                    :facts sample-facts})]
      (is (some? (validation/transact-with-validation conn tx-data))
          "Period journal must survive the validation gate"))))

(deftest missing-account-raises-actionably
  (let [conn (core/create-test-db)]
    (d/transact conn [{:db/id "mxn" :kontor.commodity/symbol "MXN"
                       :kontor.commodity/name "Peso" :kontor.commodity/precision 2
                       :kontor.commodity/iso-4217 "MXN"}
                      {:kontor.journal/code "NOM" :kontor.journal/name "Nómina"
                       :kontor.journal/type :general :kontor.journal/active true}])
    (let [db (d/db conn)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Missing GL account"
                            (pb/build-period-tx-data
                             {:db db
                              :journal (journal db)
                              :commodity (mxn db)
                              :period {:start #inst "2026-05-01"
                                       :end   #inst "2026-05-15"
                                       :payment-date #inst "2026-05-15"}
                              :facts sample-facts}))))))

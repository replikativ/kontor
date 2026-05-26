(ns kontor.payroll-mx.e2e-test
  "End-to-end MX payroll: parse vendor CSV → build GL journal → emit
   CFDI Nómina XML → record audit-doc.

   Mirrors how a consumer wires the module together for one
   semi-monthly period."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.audit-doc :as audit-doc]
            [kontor.core :as core]
            [kontor.payroll-mx.compute :as compute]
            [kontor.payroll-mx.core :as pmx-core]
            [kontor.payroll-mx.emit :as emit]
            [kontor.payroll-mx.posting-builder :as pb]
            [kontor.validation :as validation]))

;; ============================================================================
;; Bootstrap — minimal MX-style chart
;; ============================================================================

(defn- bootstrap []
  (let [conn (core/create-test-db)]
    (d/transact conn
                [{:db/id "mxn" :kontor.commodity/symbol "MXN" :kontor.commodity/name "Peso mexicano"
                  :kontor.commodity/precision 2 :kontor.commodity/iso-4217 "MXN"}
                 {:kontor.account/code "601.01" :kontor.account/path "Gastos:Sueldos"
                  :kontor.account/name "Sueldos y Salarios" :kontor.account/type :expense
                  :kontor.account/active true}
                 {:kontor.account/code "601.02" :kontor.account/path "Gastos:Aguinaldo"
                  :kontor.account/name "Aguinaldo" :kontor.account/type :expense
                  :kontor.account/active true}
                 {:kontor.account/code "601.05" :kontor.account/path "Gastos:IMSS-Patron"
                  :kontor.account/name "IMSS patrón" :kontor.account/type :expense
                  :kontor.account/active true}
                 {:kontor.account/code "601.06" :kontor.account/path "Gastos:INFONAVIT-Patron"
                  :kontor.account/name "INFONAVIT patrón" :kontor.account/type :expense
                  :kontor.account/active true}
                 {:kontor.account/code "601.84" :kontor.account/path "Gastos:Prestaciones"
                  :kontor.account/name "Otras prestaciones" :kontor.account/type :expense
                  :kontor.account/active true}
                 {:kontor.account/code "206.01" :kontor.account/path "Pasivos:SueldosPorPagar"
                  :kontor.account/name "Sueldos por pagar" :kontor.account/type :liability
                  :kontor.account/active true}
                 {:kontor.account/code "206.04" :kontor.account/path "Pasivos:ISRPorPagar"
                  :kontor.account/name "ISR por pagar" :kontor.account/type :liability
                  :kontor.account/active true}
                 {:kontor.account/code "206.05" :kontor.account/path "Pasivos:IMSSPorPagar"
                  :kontor.account/name "IMSS por pagar" :kontor.account/type :liability
                  :kontor.account/active true}
                 {:kontor.account/code "206.06" :kontor.account/path "Pasivos:INFONAVITPorPagar"
                  :kontor.account/name "INFONAVIT por pagar" :kontor.account/type :liability
                  :kontor.account/active true}
                 {:kontor.journal/code "NOM" :kontor.journal/name "Nómina"
                  :kontor.journal/type :general :kontor.journal/active true}])
    conn))

(defn- ref-eid [db a v]
  (d/q '[:find ?e . :in $ ?a ?v :where [?e ?a ?v]] db a v))

(def employer
  {:rfc "AAA010101AAA"
   :nombre "Acme México S.A. de C.V."
   :registro-patronal "B1234567890"
   :regimen-fiscal "601"})

;; ============================================================================
;; The headline E2E
;; ============================================================================

(deftest payroll-period-e2e
  (testing "CONTPAQi CSV → :payroll-facts → balanced GL → CFDI Nómina
            XML per employee → audit-doc record."
    (let [conn (bootstrap)
          ;; (1) Compute: parse vendor CSV → canonical :payroll-facts.
          compute-provider (compute/make-contpaqi-nominas-provider)
          facts-vec (pmx-core/parse-period
                     compute-provider
                     (io/resource "kontor/payroll_mx/fixtures/contpaqi-sample.csv"))
          _ (is (= 2 (count facts-vec)))

          ;; (2) Post: one balanced journal aggregating both employees.
          db (d/db conn)
          mxn-eid (ref-eid db :kontor.commodity/symbol "MXN")
          journal-eid (ref-eid db :kontor.journal/code "NOM")
          tx-data (pb/build-period-tx-data
                   {:db db :journal journal-eid :commodity mxn-eid
                    :period {:start #inst "2026-05-01"
                             :end   #inst "2026-05-15"
                             :payment-date #inst "2026-05-15"}
                    :facts facts-vec})
          tx-report (validation/transact-with-validation conn tx-data)
          _ (is (some? tx-report))

          ;; (3) Emit: one CFDI Nómina XML per (employee, period).
          emit-provider (emit/make-cfdi-nomina-provider)
          per-employee-xml
          (mapv (fn [facts]
                  (pmx-core/emit-payroll
                   emit-provider facts
                   {:employer employer
                    :employee {:rfc (:employee/rfc facts)
                               :curp (:employee/curp facts)
                               :nss "12345678901"
                               :tipo-contrato "01"
                               :tipo-regimen "02"
                               :tipo-jornada "01"
                               :periodicidad-pago :semi-monthly
                               :salario-base-cot-apor 500.00M
                               :salario-diario 500.00M
                               :antiguedad "P5Y"
                               :fecha-inicio-rel-laboral #inst "2020-01-01"
                               :num-empleado (:employee/code facts)
                               :nombre (str "Empleado " (:employee/code facts))
                               :domicilio-fiscal "45050"
                               :regimen-fiscal-receptor "605"
                               :clave-ent-fed "NLE"}
                    :no-certificado "30001000000400002434"
                    :certificado    "MIIF..."
                    :lugar-expedicion "45050"}))
                facts-vec)
          _ (is (= 2 (count per-employee-xml)))]

      (testing "Every emitted XML is tagged with the audit-doc category +
                language per ADR-082."
        (doseq [r per-employee-xml]
          (is (= :payroll-filing (:kontor.audit-doc/category r)))
          (is (= :es-mx (:kontor.audit-doc/language r)))
          (is (= :payroll-cfdi-xml (:kontor.audit-doc/type r)))
          (is (re-find #"TipoDeComprobante=\"N\"" (:xml r)))
          (is (str/includes? (:xml r) "nomina12"))))

      (testing "Audit-doc creation persists the category + language."
        (doseq [r per-employee-xml]
          (let [code (str "CFDI-NOM-" (System/nanoTime) "-" (rand-int 1000000))]
            (audit-doc/create-doc!
             conn
             {:code code
              :type (:kontor.audit-doc/type r)
              :title (:kontor.audit-doc/title r)
              :description (:kontor.audit-doc/description r)
              :storage-uri (str "s3://cfdi-nomina/" code ".xml")
              :content-hash "deadbeef"})
            (d/transact conn
                        [{:kontor.audit-doc/code code
                          :kontor.audit-doc/category (:kontor.audit-doc/category r)
                          :kontor.audit-doc/language (:kontor.audit-doc/language r)}])
            (let [pulled (audit-doc/pull-doc (d/db conn) code)]
              (is (= :payroll-filing (:kontor.audit-doc/category pulled)))
              (is (= :es-mx (:kontor.audit-doc/language pulled))))))))))

(deftest aspel-end-to-end-also-balances
  (testing "Aspel NOI provider produces facts that route through the
            same GL/CFDI pipeline."
    (let [conn (bootstrap)
          provider (compute/make-aspel-noi-provider)
          facts (pmx-core/parse-period
                 provider
                 (io/resource "kontor/payroll_mx/fixtures/aspel-sample.csv"))
          db (d/db conn)
          mxn-eid (ref-eid db :kontor.commodity/symbol "MXN")
          journal-eid (ref-eid db :kontor.journal/code "NOM")
          tx-data (pb/build-period-tx-data
                   {:db db :journal journal-eid :commodity mxn-eid
                    :period {:start #inst "2026-05-01"
                             :end   #inst "2026-05-15"
                             :payment-date #inst "2026-05-15"}
                    :facts facts})]
      (is (some? (validation/transact-with-validation conn tx-data))))))

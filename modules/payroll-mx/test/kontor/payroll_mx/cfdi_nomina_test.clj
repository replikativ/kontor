(ns kontor.payroll-mx.cfdi-nomina-test
  "CFDI Nómina 1.2 XML round-trip — the load-bearing emit shape.

   The XML produced here is **unsigned**: a PAC stamps it with TFD
   (TimbreFiscalDigital). We verify the structural shape against
   the public SAT spec (Anexo 20 + Catálogos de Nómina v1.2)."
  (:require [clojure.data.xml :as xml]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kontor.payroll-mx.cfdi-nomina :as nomina]
            [kontor.payroll-mx.core :as core]
            [kontor.payroll-mx.emit :as emit]))

(def sample-facts
  (core/make-payroll-facts
   {:employee/rfc "ABCD800101AB1"
    :employee/curp "ABCD800101HDFRRR01"
    :employee/code "E001"
    :employee/name "Juan Pérez García"
    :kontor.period/start #inst "2026-05-01"
    :kontor.period/end   #inst "2026-05-15"
    :kontor.period/payment-date #inst "2026-05-15"
    :wage-types [{:wage-type :sueldo            :amount 7500.00M :commodity "MXN"}
                 {:wage-type :hora-extra-doble  :amount 250.00M  :commodity "MXN"}
                 {:wage-type :vales-de-despensa :amount 1000.00M :commodity "MXN"}
                 {:wage-type :isr-retencion     :amount 750.00M  :commodity "MXN"}
                 {:wage-type :imss-trabajador   :amount 150.00M  :commodity "MXN"}
                 {:wage-type :subsidio-al-empleo :amount 25.00M  :commodity "MXN"}
                 ;; Employer-only (should NOT appear in the CFDI):
                 {:wage-type :imss-patron       :amount 400.00M  :commodity "MXN"}]}))

(def sample-employer
  {:rfc "AAA010101AAA"
   :nombre "Acme México S.A. de C.V."
   :registro-patronal "B1234567890"
   :regimen-fiscal "601"})

(def sample-employee
  {:rfc "ABCD800101AB1"
   :curp "ABCD800101HDFRRR01"
   :nss "12345678901"
   :tipo-contrato "01"
   :tipo-regimen  "02"
   :tipo-jornada  "01"
   :periodicidad-pago :semi-monthly
   :salario-base-cot-apor 500.00M
   :salario-diario 500.00M
   :antiguedad "P5Y"
   :fecha-inicio-rel-laboral #inst "2018-01-15"
   :num-empleado "E001"
   :nombre "Juan Pérez García"
   :domicilio-fiscal "45050"
   :regimen-fiscal-receptor "605"
   :clave-ent-fed "NLE"})

;; ============================================================================
;; Complemento shape
;; ============================================================================

(deftest complemento-emits-nomina12-root
  (let [s (nomina/emit-complemento-string
           (nomina/nomina-complemento
            {:facts sample-facts
             :employer sample-employer
             :employee sample-employee
             :tipo :ordinary}))]
    (is (str/includes? s "nomina12") "Uses nomina12 namespace")
    (is (re-find #"Version=\"1\.2\"" s))
    (is (re-find #"TipoNomina=\"O\"" s) "Ordinary nómina → 'O'")
    (is (re-find #"FechaPago=\"2026-05-15\"" s))
    (is (re-find #"FechaInicialPago=\"2026-05-01\"" s))
    (is (re-find #"FechaFinalPago=\"2026-05-15\"" s))))

(deftest complemento-emits-emisor-with-registro-patronal
  (let [s (nomina/emit-complemento-string
           (nomina/nomina-complemento
            {:facts sample-facts :employer sample-employer
             :employee sample-employee}))]
    (is (re-find #"RegistroPatronal=\"B1234567890\"" s))))

(deftest complemento-emits-receptor-with-curp-and-nss
  (let [s (nomina/emit-complemento-string
           (nomina/nomina-complemento
            {:facts sample-facts :employer sample-employer
             :employee sample-employee}))]
    (is (re-find #"Curp=\"ABCD800101HDFRRR01\"" s))
    (is (re-find #"NumSeguridadSocial=\"12345678901\"" s))
    (is (re-find #"PeriodicidadPago=\"04\"" s)
        ":semi-monthly → SAT c_PeriodicidadPago '04' (quincenal)")
    (is (re-find #"TipoContrato=\"01\"" s))))

(deftest complemento-emits-percepciones-block
  (let [s (nomina/emit-complemento-string
           (nomina/nomina-complemento
            {:facts sample-facts :employer sample-employer
             :employee sample-employee}))]
    (is (re-find #":Percepciones " s)
        "Percepciones block element present")
    (is (str/includes? s "http://www.sat.gob.mx/nomina12")
        "nomina12 namespace URL present in the envelope")
    (testing "Sueldo (TipoPercepcion=001) recorded as gravado"
      (is (re-find #"TipoPercepcion=\"001\"" s))
      (is (re-find #"ImporteGravado=\"7500\.00\"" s)))
    (testing "Vales de despensa (TipoPercepcion=029) recorded as exento"
      (is (re-find #"TipoPercepcion=\"029\"" s))
      (is (re-find #"ImporteExento=\"1000\.00\"" s)))))

(deftest complemento-emits-deducciones-block
  (let [s (nomina/emit-complemento-string
           (nomina/nomina-complemento
            {:facts sample-facts :employer sample-employer
             :employee sample-employee}))]
    (is (re-find #"TipoDeduccion=\"002\"" s)
        "ISR retenido → TipoDeduccion '002'")
    (is (re-find #"Importe=\"750\.00\"" s))
    (is (re-find #"TipoDeduccion=\"001\"" s)
        "IMSS trabajador → '001'")))

(deftest complemento-emits-otros-pagos-block
  (let [s (nomina/emit-complemento-string
           (nomina/nomina-complemento
            {:facts sample-facts :employer sample-employer
             :employee sample-employee}))]
    (is (re-find #"TipoOtroPago=\"002\"" s)
        "Subsidio al empleo → TipoOtroPago '002'")
    (is (re-find #"Importe=\"25\.00\"" s))))

(deftest complemento-omits-employer-only-rows-on-receptor-side
  (testing "IMSS patrón is in the GL but NOT in the worker's CFDI."
    (let [s (nomina/emit-complemento-string
             (nomina/nomina-complemento
              {:facts sample-facts :employer sample-employer
               :employee sample-employee}))]
      ;; The 400.00 amount appears nowhere as a Percepcion (gravado or exento).
      (is (not (re-find #"ImporteGravado=\"400\.00\"" s)))
      (is (not (re-find #"ImporteExento=\"400\.00\"" s)))
      (is (not (re-find #"Importe=\"400\.00\"" s))))))

(deftest complemento-totals-roll-up
  (let [s (nomina/emit-complemento-string
           (nomina/nomina-complemento
            {:facts sample-facts :employer sample-employer
             :employee sample-employee}))]
    ;; Worker-side percepciones = sueldo 7500 + hora-extra 250 + vales 1000 = 8750
    (is (re-find #"TotalPercepciones=\"8750\.00\"" s))
    ;; Total deducciones = ISR 750 + IMSS-trab 150 = 900
    (is (re-find #"TotalDeducciones=\"900\.00\"" s))
    ;; Total otros pagos = subsidio 25
    (is (re-find #"TotalOtrosPagos=\"25\.00\"" s))))

;; ============================================================================
;; Full envelope (CFDI 4.0 N + nómina complemento)
;; ============================================================================

(deftest envelope-uses-tipo-comprobante-n
  (let [xml (nomina/nomina-envelope
             {:facts sample-facts
              :employer sample-employer
              :employee sample-employee
              :no-certificado "30001000000400002434"
              :certificado    "MIIF...test..."
              :lugar-expedicion "45050"})]
    (is (re-find #"TipoDeComprobante=\"N\"" xml)
        "CFDI Nómina is TipoDeComprobante='N' (Nómina)")
    (is (re-find #"Version=\"4\.0\"" xml))
    (is (str/includes? xml "nomina12")
        "Complemento namespace nomina12 present")))

(deftest envelope-routes-the-nomina-complemento-inside-comprobante
  (let [xml (nomina/nomina-envelope
             {:facts sample-facts
              :employer sample-employer
              :employee sample-employee
              :no-certificado "30001000000400002434"
              :certificado    "MIIF..."
              :lugar-expedicion "45050"})]
    (is (re-find #"Complemento" xml)
        "The <cfdi:Complemento> block wraps the nómina12 element")))

;; ============================================================================
;; EmitProvider behaviour
;; ============================================================================

(deftest emit-provider-tags-audit-doc-fields
  (let [provider (emit/make-cfdi-nomina-provider)
        result (core/emit-payroll provider sample-facts
                                  {:employer sample-employer
                                   :employee sample-employee
                                   :no-certificado "30001000000400002434"
                                   :certificado    "MIIF..."
                                   :lugar-expedicion "45050"})]
    (is (= :mx/cfdi-nomina-1.2 (:emit-format result))
        "Emit format keyword identifies the version")
    (is (= :mx/cfdi-nomina-1.2 (core/emit-format provider)))
    (is (= :payroll-filing (:audit-doc/category result))
        "Audit-doc category tag per ADR-082 + task spec")
    (is (= :es-mx (:audit-doc/language result))
        "Audit-doc language tag per ADR-082")
    (is (= :payroll-cfdi-xml (:audit-doc/type result)))
    (is (string? (:xml result)))
    (is (string? (:complemento-xml result)))
    (is (string? (:audit-doc/title result)))
    (is (string? (:audit-doc/description result)))))

(deftest extraordinaria-tipo-emits-E
  (testing "Aguinaldo period → tipo-nomina='E' (extraordinaria)."
    (let [xml (nomina/emit-complemento-string
               (nomina/nomina-complemento
                {:facts sample-facts :employer sample-employer
                 :employee sample-employee
                 :tipo :extraordinary}))]
      (is (re-find #"TipoNomina=\"E\"" xml)))))

;; ============================================================================
;; The load-bearing payroll roundtrip — facts → CFDI → re-parse
;; ============================================================================

(deftest cfdi-nomina-roundtrip
  (testing "We can roundtrip :payroll-facts → CFDI Nómina XML →
            re-parse and recover the load-bearing fields."
    (let [provider (emit/make-cfdi-nomina-provider)
          {:keys [complemento-xml]}
          (core/emit-payroll provider sample-facts
                             {:employer sample-employer
                              :employee sample-employee
                              :no-certificado "30001000000400002434"
                              :certificado    "MIIF..."
                              :lugar-expedicion "45050"})
          parsed (xml/parse-str complemento-xml)
          attrs (:attrs parsed)]
      (is (= "1.2" (:Version attrs)))
      (is (= "O" (:TipoNomina attrs)))
      (is (= "2026-05-15" (:FechaPago attrs)))
      (is (= "2026-05-01" (:FechaInicialPago attrs)))
      (is (= "2026-05-15" (:FechaFinalPago attrs)))
      ;; Totals (worker-side only):
      (is (= "8750.00" (:TotalPercepciones attrs)))
      (is (= "900.00"  (:TotalDeducciones attrs)))
      (is (= "25.00"   (:TotalOtrosPagos attrs))))))

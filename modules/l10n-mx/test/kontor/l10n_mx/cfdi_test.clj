(ns kontor.l10n-mx.cfdi-test
  "Tests for CFDI 4.0 emission, focused on ADR-025 document-composition
   semantics. Sample fields are illustrative; real CSD signing + PAC
   submission is a partner concern."
  (:require [clojure.data.xml :as xml]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.l10n-mx.cfdi :as cfdi]))

(def sample-invoice-base
  "Minimum-viable CFDI 4.0 invoice fields. Real CFDIs have many more."
  {:cfdi/serie  "A"
   :cfdi/folio  "123"
   :cfdi/fecha  #inst "2026-05-11T10:00:00Z"
   :cfdi/forma-pago "03"                        ; transfer
   :cfdi/no-certificado "30001000000400002434"
   :cfdi/certificado "MIIF...test-cert..."
   :cfdi/subtotal 1000.00M
   :cfdi/total    1160.00M
   :cfdi/moneda   "MXN"
   :cfdi/tipo-de-comprobante :income
   :cfdi/exportacion :not-applicable
   :cfdi/metodo-pago :single-payment
   :cfdi/lugar-expedicion "45050"
   :cfdi/emisor   {:rfc "AAA010101AAA"
                   :nombre "Acme Mexico SA de CV"
                   :regimen-fiscal "601"}
   :cfdi/receptor {:rfc "XAXX010101000"
                   :nombre "PUBLICO EN GENERAL"
                   :domicilio-fiscal "45050"
                   :regimen-fiscal-receptor "616"
                   :uso-cfdi "S01"}
   :cfdi/conceptos [{:clave-prodserv "01010101"
                     :cantidad 1.00M
                     :clave-unidad "H87"
                     :descripcion "Producto demo"
                     :valor-unitario 1000.00M
                     :importe 1000.00M
                     :objeto-imp "02"
                     :impuestos {:traslados
                                 [{:base 1000.00M :impuesto "002"
                                   :tipo-factor "Tasa" :tasa-o-cuota 0.16M
                                   :importe 160.00M}]}}]
   :cfdi/impuestos {:total-impuestos-trasladados 160.00M
                    :traslados [{:base 1000.00M :impuesto "002"
                                 :tipo-factor "Tasa"
                                 :tasa-o-cuota 0.16M
                                 :importe 160.00M}]}})

;; ============================================================================
;; Envelope shape
;; ============================================================================

(deftest cfdi-envelope-has-required-attributes
  (let [doc (cfdi/invoice-element sample-invoice-base)
        s (cfdi/emit-string doc)]
    (is (re-find #"Version=\"4\.0\"" s))
    (is (re-find #"Fecha=\"2026-05-11T10:00:00\"" s))
    (is (re-find #"TipoDeComprobante=\"I\"" s)
        "Income → 'I'")
    (is (re-find #"Exportacion=\"01\"" s)
        ":not-applicable → '01'")
    (is (re-find #"MetodoPago=\"PUE\"" s)
        ":single-payment → 'PUE'")
    (is (re-find #"Moneda=\"MXN\"" s))))

(deftest cfdi-emits-emisor-receptor-conceptos
  (let [s (cfdi/emit-string (cfdi/invoice-element sample-invoice-base))]
    (is (re-find #"Emisor[^>]+Rfc=\"AAA010101AAA\"" s))
    (is (re-find #"Receptor[^>]+Rfc=\"XAXX010101000\"" s))
    (is (re-find #"Receptor[^>]+RegimenFiscalReceptor=\"616\"" s))
    (is (re-find #"Receptor[^>]+UsoCFDI=\"S01\"" s))
    (is (re-find #"DomicilioFiscalReceptor=\"45050\"" s))
    (is (re-find #"<[^/>]*Concepto[^>]+ClaveProdServ=\"01010101\"" s))))

(deftest cfdi-emits-traslados-block
  (let [s (cfdi/emit-string (cfdi/invoice-element sample-invoice-base))]
    (is (clojure.string/includes? s "TasaOCuota=\"0.160000\"")
        "IVA rate emitted as 0.160000 (6 decimal places per Anexo 20)")
    (is (clojure.string/includes? s "Importe=\"160.00\""))
    (is (clojure.string/includes? s "Traslado"))))

;; ============================================================================
;; ADR-025: complemento composition
;; ============================================================================

(deftest cfdi-with-three-complementos-inline
  (testing "Direct splicing via :cfdi/complementos (no DB) — the
            envelope must end with three complemento fragments in
            the order supplied."
    (let [pagos-frag "<pago20:Pagos xmlns:pago20=\"http://www.sat.gob.mx/Pagos20\" Version=\"2.0\"/>"
          carta-frag "<cartaporte31:CartaPorte xmlns:cartaporte31=\"http://www.sat.gob.mx/CartaPorte31\" Version=\"3.1\"/>"
          tfd-frag   "<tfd:TimbreFiscalDigital xmlns:tfd=\"http://www.sat.gob.mx/TimbreFiscalDigital\" UUID=\"12345678-ABCD-1234-EFGH-1234567890AB\"/>"
          doc (cfdi/invoice-element (assoc sample-invoice-base
                                           :cfdi/complementos [pagos-frag carta-frag tfd-frag]))
          s   (cfdi/emit-string doc)]
      (is (re-find #"<[^/>]*Complemento>" s))
      (is (re-find #"pago20:Pagos" s)
          "Pagos 2.0 fragment present in envelope")
      (is (re-find #"cartaporte31:CartaPorte" s)
          "Carta Porte 3.1 fragment present in envelope")
      (is (re-find #"TimbreFiscalDigital" s)
          "TFD (PAC stamp) fragment present in envelope")
      (testing "Fragment ordering is preserved (Pagos → Carta Porte → TFD)"
        (let [pagos-idx (.indexOf s "pago20:Pagos")
              carta-idx (.indexOf s "cartaporte31:CartaPorte")
              tfd-idx   (.indexOf s "TimbreFiscalDigital")]
          (is (< pagos-idx carta-idx tfd-idx)
              "Sequence in the emitted XML matches the input order"))))))

(deftest cfdi-without-complementos-omits-the-block
  (let [s (cfdi/emit-string (cfdi/invoice-element sample-invoice-base))]
    (is (not (re-find #"<[^/>]*Complemento>" s))
        "No <Complemento> block when no complementos are supplied")))

;; ============================================================================
;; ADR-025 + ADR-024 integration: complementos persisted as data
;; ============================================================================

(defn- minimal-tx!
  [conn]
  (d/transact conn
              [{:db/id -1 :kontor.commodity/symbol "MXN" :kontor.commodity/name "Mexican Peso"
                :kontor.commodity/precision 2 :kontor.commodity/iso-4217 "MXN"}
               {:db/id -2 :kontor.account/path "Assets:Receivable"
                :kontor.account/name "AR" :kontor.account/type :asset :kontor.account/active true}
               {:db/id -3 :kontor.account/path "Income:Sales"
                :kontor.account/name "Sales" :kontor.account/type :income :kontor.account/active true}
               {:db/id -4 :kontor.journal/code "INV-MX" :kontor.journal/name "Ventas México"
                :kontor.journal/type :sale :kontor.journal/active true}
               {:db/id -10
                :kontor.transaction/external-id    "INV-2026-MX-0001"
                :kontor.transaction/journal        -4
                :kontor.transaction/effective-date #inst "2026-05-11"
                :kontor.transaction/narration      "Sample CFDI"}])
  (:db/id (d/entity (d/db conn) [:kontor.transaction/external-id "INV-2026-MX-0001"])))

(deftest assemble-from-transaction-pulls-complementos-in-sequence
  (testing "**The load-bearing ADR-025 test.** Persist three
            complementos on a transaction with explicit sequence
            numbers, then assemble the CFDI envelope — the resulting
            XML must contain all three in the sequence order, sourced
            from datalog."
    (let [conn (core/create-test-db)
          tx   (minimal-tx! conn)
          pagos-payload "<pago20:Pagos xmlns:pago20=\"http://www.sat.gob.mx/Pagos20\" Version=\"2.0\"/>"
          carta-payload "<cartaporte31:CartaPorte xmlns:cartaporte31=\"http://www.sat.gob.mx/CartaPorte31\" Version=\"3.1\"/>"
          tfd-payload   "<tfd:TimbreFiscalDigital xmlns:tfd=\"http://www.sat.gob.mx/TimbreFiscalDigital\" UUID=\"AAAA-BBBB-CCCC-DDDD\"/>"
          ;; Insert deliberately out of order — the sequence-sort must
          ;; produce the right final order regardless of insertion order.
          _ (d/transact conn
                        [{:db/id -100
                          :kontor.complemento/transaction tx
                          :kontor.complemento/namespace   "http://www.sat.gob.mx/TimbreFiscalDigital"
                          :kontor.complemento/format      :mx/cfdi-tfd-1.1
                          :kontor.complemento/sequence    9999            ; TFD always last
                          :kontor.complemento/payload     tfd-payload
                          :kontor.complemento/active      true}
                         {:db/id -200
                          :kontor.complemento/transaction tx
                          :kontor.complemento/namespace   "http://www.sat.gob.mx/CartaPorte31"
                          :kontor.complemento/format      :mx/cfdi-carta-porte-3.1
                          :kontor.complemento/sequence    200
                          :kontor.complemento/payload     carta-payload
                          :kontor.complemento/active      true}
                         {:db/id -300
                          :kontor.complemento/transaction tx
                          :kontor.complemento/namespace   "http://www.sat.gob.mx/Pagos20"
                          :kontor.complemento/format      :mx/cfdi-pagos-2.0
                          :kontor.complemento/sequence    100
                          :kontor.complemento/payload     pagos-payload
                          :kontor.complemento/active      true}
                         {:db/id tx :kontor.transaction/complementos [-100 -200 -300]}])
          xml-str (cfdi/assemble-from-transaction conn tx sample-invoice-base)]
      (is (string? xml-str))
      (is (re-find #"<[^/>]*Complemento>" xml-str))
      (let [pagos-idx (.indexOf xml-str "pago20:Pagos")
            carta-idx (.indexOf xml-str "cartaporte31:CartaPorte")
            tfd-idx   (.indexOf xml-str "TimbreFiscalDigital")]
        (is (pos? pagos-idx))
        (is (pos? carta-idx))
        (is (pos? tfd-idx))
        (is (< pagos-idx carta-idx tfd-idx)
            "Datalog-sourced complementos must emit in :kontor.complemento/sequence order")))))

(deftest inactive-complementos-are-skipped
  (testing "A complemento with :kontor.complemento/active false is excluded
            from the assembled envelope (soft-supersede)"
    (let [conn (core/create-test-db)
          tx   (minimal-tx! conn)
          _ (d/transact conn
                        [{:db/id -1
                          :kontor.complemento/transaction tx
                          :kontor.complemento/namespace   "http://www.sat.gob.mx/Pagos20"
                          :kontor.complemento/format      :mx/cfdi-pagos-2.0
                          :kontor.complemento/sequence    100
                          :kontor.complemento/payload     "<pago20:Pagos xmlns:pago20=\"http://www.sat.gob.mx/Pagos20\" Version=\"2.0\" obsolete=\"yes\"/>"
                          :kontor.complemento/active      false}
                         {:db/id tx :kontor.transaction/complementos -1}])
          xml-str (cfdi/assemble-from-transaction conn tx sample-invoice-base)]
      (is (not (re-find #"obsolete" xml-str))
          "Inactive complemento payload must not appear in the output"))))

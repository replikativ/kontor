(ns kontor.payroll-mx.emit
  "MxCfdiNominaEmitProvider — produces the unsigned CFDI Nómina 1.2
   XML payload for a `:payroll-facts` record. ADR-082.

   The XML is **consumer-signed** — a PAC (Proveedor Autorizado de
   Certificación) stamps it with a TFD (TimbreFiscalDigital) and
   returns a UUID. The kernel records the timbre as an
   `:audit-doc` (category `:payroll-filing`, language `:es-mx`)
   when supplied.

   ## Result map shape

   `emit-payroll` returns:
     {:xml             <string>          ; the CFDI envelope
      :complemento-xml <string>          ; just the <nomina12:Nomina>
      :emit-format     :mx/cfdi-nomina-1.2
      :audit-doc/category :payroll-filing
      :audit-doc/language :es-mx
      :audit-doc/type     :payroll-cfdi-xml
      :audit-doc/title    \"CFDI Nómina <employee-code> <period>\"
      :audit-doc/description \"<facts summary in es-mx>\"}

   The caller persists the XML, hashes it, and creates an
   :audit-doc via `kontor.audit-doc/create-doc!`. The :audit-doc
   eid is then attached to the :transaction (`:kontor.transaction/audit-
   docs`) or the :status-history row that records the period close.

   This module does NOT submit to a PAC; that is partner-side."
  (:require [kontor.payroll-mx.cfdi-nomina :as cfdi-nomina]
            [kontor.payroll-mx.core :as core]))

(defrecord MxCfdiNominaEmitProvider []
  core/MxCfdiEmitter
  (emit-format [_] :mx/cfdi-nomina-1.2)
  (emit-payroll [_ facts opts]
    (let [{:keys [employer employee tipo
                  serie folio fecha lugar-expedicion
                  no-certificado certificado]} opts
          complemento-xml
          (cfdi-nomina/emit-complemento-string
           (cfdi-nomina/nomina-complemento
            {:facts facts
             :employer employer
             :employee employee
             :tipo (or tipo :ordinary)}))
          envelope-xml
          (cfdi-nomina/nomina-envelope
           {:facts facts
            :employer employer
            :employee employee
            :tipo (or tipo :ordinary)
            :serie serie
            :folio folio
            :fecha fecha
            :lugar-expedicion lugar-expedicion
            :no-certificado no-certificado
            :certificado certificado})
          title (str "CFDI Nómina " (:employee/code facts)
                     " " (:period/start facts) ".." (:period/end facts))
          desc (str "Recibo de nómina " (or (get cfdi-nomina/tipos-nomina
                                                 (or tipo :ordinary))
                                            "O")
                    " — empleado " (:employee/code facts)
                    " periodo " (:period/start facts)
                    " a " (:period/end facts))]
      {:xml envelope-xml
       :complemento-xml complemento-xml
       :emit-format :mx/cfdi-nomina-1.2
       :audit-doc/category :payroll-filing
       :audit-doc/language :es-mx
       :audit-doc/type :payroll-cfdi-xml
       :audit-doc/title title
       :audit-doc/description desc})))

(defn make-cfdi-nomina-provider
  "Construct the default MxCfdiNominaEmitProvider."
  []
  (->MxCfdiNominaEmitProvider))

(ns kontor.l10n-mx.cfdi
  "CFDI 4.0 — Mexican e-invoice XML envelope builder.

   Per ADR-017 the module ships the **pure XML emitter**; signing
   with the CSD (Certificado de Sello Digital) and PAC submission
   live in partner adapters (e.g. `kontor-l10n-mx-pac-facturama`),
   not in the kernel.

   Per ADR-018 the resulting TFD (TimbreFiscalDigital) UUID returned
   by the PAC lands in `:attestation/token` under format `:mx/cfdi-uuid`.

   Per **ADR-025** the CFDI envelope is one root + N stacked
   complementos. The emitter assembles the `<cfdi:Complemento>` block
   from `:transaction/complementos` sorted by `:complemento/sequence`,
   splicing each payload XML into the parent in order.

   ## Known emit gaps (deferred)

   - Cadena original + sello (signing) — partner-side concern
   - Specific complemento builders (Pagos, Carta Porte, Nómina) —
     each is its own ADR-sized slice
   - XSD validation — emit only; caller validates"
  (:require [clojure.data.xml :as xml]
            [clojure.string :as str]))

(xml/alias-uri 'cfdi "http://www.sat.gob.mx/cfd/4")

(def document-types
  "CFDI 4.0 TipoDeComprobante codes per Anexo 20."
  {:income       "I"     ; Ingreso (sale invoice)
   :expense      "E"     ; Egreso (credit note)
   :transfer     "T"     ; Traslado (goods transfer, pairs w/ Carta Porte)
   :payroll      "N"     ; Nómina
   :payment      "P"})   ; Pago receipt (pairs w/ Pagos complement)

(def export-codes
  "CFDI 4.0 Exportacion codes."
  {:not-applicable        "01"
   :definitive-export     "02"   ; export with payment of tax
   :temporary             "03"
   :non-applicable-virtual "04"})

(def payment-method-codes
  "CFDI 4.0 MetodoPago — PUE / PPD."
  {:single-payment      "PUE"    ; Pago en Una sola Exhibición
   :installments        "PPD"})  ; Pago en Parcialidades o Diferido

;; ============================================================================
;; Helpers
;; ============================================================================

(defn- fmt-money
  "CFDI amounts: BigDecimal with up to 6 decimal places. We use 2 for
   subtotal/total + 4 for tax rates."
  ([n] (fmt-money n 2))
  ([n scale]
   (-> ^java.math.BigDecimal (bigdec n)
       (.setScale (int scale) java.math.RoundingMode/HALF_EVEN)
       .toPlainString)))

(defn- iso-datetime
  "CFDI 4.0 Fecha format: YYYY-MM-DDTHH:MM:SS (no offset, no millis).
   Per Anexo 20 the time is interpreted in the emitter's local zone."
  ^String [^java.util.Date d]
  (let [ldt (-> d .toInstant (.atZone java.time.ZoneOffset/UTC) .toLocalDateTime)]
    (format "%04d-%02d-%02dT%02d:%02d:%02d"
            (.getYear ldt) (.getMonthValue ldt) (.getDayOfMonth ldt)
            (.getHour ldt) (.getMinute ldt) (.getSecond ldt))))

;; ============================================================================
;; Element builders
;; ============================================================================

(defn- emisor-element
  [{:keys [rfc nombre regimen-fiscal]}]
  (xml/element ::cfdi/Emisor
               {:Rfc rfc
                :Nombre nombre
                :RegimenFiscal regimen-fiscal}))

(defn- receptor-element
  [{:keys [rfc nombre domicilio-fiscal regimen-fiscal-receptor uso-cfdi
           residencia-fiscal num-reg-id-trib]}]
  (xml/element
   ::cfdi/Receptor
   (cond-> {:Rfc rfc
            :Nombre nombre
            :DomicilioFiscalReceptor domicilio-fiscal
            :RegimenFiscalReceptor   regimen-fiscal-receptor
            :UsoCFDI                 uso-cfdi}
     residencia-fiscal (assoc :ResidenciaFiscal residencia-fiscal)
     num-reg-id-trib   (assoc :NumRegIdTrib num-reg-id-trib))))

(defn- concepto-element
  [{:keys [clave-prodserv no-identificacion cantidad clave-unidad
           descripcion valor-unitario importe descuento objeto-imp
           impuestos]
    :or {objeto-imp "02"}}]
  (xml/element
   ::cfdi/Concepto
   (cond-> {:ClaveProdServ clave-prodserv
            :Cantidad      (fmt-money cantidad)
            :ClaveUnidad   clave-unidad
            :Descripcion   descripcion
            :ValorUnitario (fmt-money valor-unitario)
            :Importe       (fmt-money importe)
            :ObjetoImp     objeto-imp}
     no-identificacion (assoc :NoIdentificacion no-identificacion)
     descuento (assoc :Descuento (fmt-money descuento)))
   (when impuestos
     (xml/element
      ::cfdi/Impuestos {}
      (when-let [trs (:traslados impuestos)]
        (xml/element
         ::cfdi/Traslados {}
         (for [{:keys [base impuesto tipo-factor tasa-o-cuota importe]} trs]
           (xml/element ::cfdi/Traslado
                        {:Base       (fmt-money base)
                         :Impuesto   impuesto
                         :TipoFactor tipo-factor
                         :TasaOCuota (fmt-money tasa-o-cuota 6)
                         :Importe    (fmt-money importe)}))))
      (when-let [rets (:retenciones impuestos)]
        (xml/element
         ::cfdi/Retenciones {}
         (for [{:keys [base impuesto tipo-factor tasa-o-cuota importe]} rets]
           (xml/element ::cfdi/Retencion
                        {:Base       (fmt-money base)
                         :Impuesto   impuesto
                         :TipoFactor tipo-factor
                         :TasaOCuota (fmt-money tasa-o-cuota 6)
                         :Importe    (fmt-money importe)}))))))))

(defn- impuestos-element
  [{:keys [total-impuestos-trasladados total-impuestos-retenidos
           traslados retenciones]}]
  (xml/element
   ::cfdi/Impuestos
   (cond-> {}
     total-impuestos-trasladados
     (assoc :TotalImpuestosTrasladados (fmt-money total-impuestos-trasladados))
     total-impuestos-retenidos
     (assoc :TotalImpuestosRetenidos (fmt-money total-impuestos-retenidos)))
   (when traslados
     (xml/element
      ::cfdi/Traslados {}
      (for [{:keys [base impuesto tipo-factor tasa-o-cuota importe]} traslados]
        (xml/element ::cfdi/Traslado
                     {:Base       (fmt-money base)
                      :Impuesto   impuesto
                      :TipoFactor tipo-factor
                      :TasaOCuota (fmt-money tasa-o-cuota 6)
                      :Importe    (fmt-money importe)}))))
   (when retenciones
     (xml/element
      ::cfdi/Retenciones {}
      (for [{:keys [impuesto importe]} retenciones]
        (xml/element ::cfdi/Retencion
                     {:Impuesto impuesto
                      :Importe  (fmt-money importe)}))))))

;; ============================================================================
;; Envelope assembly
;; ============================================================================

(defn invoice-element
  "Build a `<cfdi:Comprobante>` XML element tree for a CFDI 4.0
   invoice. Caller plugs the result into `kontor.l10n-mx.cfdi/emit-string`.

   Input shape:
     {:cfdi/version    \"4.0\"
      :cfdi/serie      \"A\"
      :cfdi/folio      \"123\"
      :cfdi/fecha       #inst …
      :cfdi/forma-pago \"03\"             ; c_FormaPago
      :cfdi/no-certificado \"30001000…\"
      :cfdi/certificado    \"MIIF…\"     ; base64 X.509
      :cfdi/subtotal       1000.00M
      :cfdi/descuento      0M
      :cfdi/moneda         \"MXN\"
      :cfdi/tipo-cambio    nil            ; required if Moneda ≠ MXN
      :cfdi/total          1160.00M
      :cfdi/tipo-de-comprobante :income   ; → 'I'
      :cfdi/exportacion    :not-applicable
      :cfdi/metodo-pago    :single-payment
      :cfdi/lugar-expedicion \"45050\"    ; postal code
      :cfdi/emisor   {:rfc :nombre :regimen-fiscal}
      :cfdi/receptor {:rfc :nombre :domicilio-fiscal
                       :regimen-fiscal-receptor :uso-cfdi}
      :cfdi/conceptos    [{…concepto…}]
      :cfdi/impuestos    {…totals…}
      :cfdi/complementos [<xml-fragment-string> …]}

   `:cfdi/complementos` is the ordered list of complemento XML payloads
   to splice into `<cfdi:Complemento>` per ADR-025 — typically built
   from `:transaction/complementos` ordered by `:complemento/sequence`."
  [{:keys [cfdi/version cfdi/serie cfdi/folio cfdi/fecha
           cfdi/forma-pago cfdi/no-certificado cfdi/certificado
           cfdi/subtotal cfdi/descuento cfdi/moneda cfdi/tipo-cambio
           cfdi/total cfdi/tipo-de-comprobante cfdi/exportacion
           cfdi/metodo-pago cfdi/lugar-expedicion cfdi/condiciones-de-pago
           cfdi/emisor cfdi/receptor cfdi/conceptos cfdi/impuestos
           cfdi/complementos]
    :or {version "4.0"
         moneda "MXN"
         exportacion :not-applicable
         metodo-pago :single-payment}}]
  (xml/element
   ::cfdi/Comprobante
   (cond-> {:Version            version
            :Fecha              (iso-datetime fecha)
            :FormaPago          forma-pago
            :NoCertificado      no-certificado
            :Certificado        certificado
            :SubTotal           (fmt-money subtotal)
            :Moneda             moneda
            :Total              (fmt-money total)
            :TipoDeComprobante  (get document-types tipo-de-comprobante)
            :Exportacion        (get export-codes exportacion)
            :MetodoPago         (get payment-method-codes metodo-pago)
            :LugarExpedicion    lugar-expedicion
            ;; Empty Sello — caller signs after assembly.
            :Sello              ""}
     serie     (assoc :Serie serie)
     folio     (assoc :Folio folio)
     tipo-cambio (assoc :TipoCambio (fmt-money tipo-cambio 6))
     descuento (assoc :Descuento (fmt-money descuento))
     condiciones-de-pago (assoc :CondicionesDePago condiciones-de-pago))
   (emisor-element emisor)
   (receptor-element receptor)
   (xml/element ::cfdi/Conceptos {}
                (map concepto-element conceptos))
   (when impuestos (impuestos-element impuestos))
   ;; ADR-025: complementos splice in as opaque XML fragments. We
   ;; parse each fragment string and add it as a child. The kernel
   ;; doesn't validate against complemento-specific XSDs — that's the
   ;; emitter / consumer concern.
   (when (seq complementos)
     (xml/element ::cfdi/Complemento {}
                  (map (fn [^String frag] (xml/parse-str frag)) complementos)))))

(defn emit-string
  "Serialize an `invoice-element` tree to canonical XML."
  ^String [doc]
  (xml/emit-str doc))

(defn assemble-from-transaction
  "Build the CFDI envelope from a transaction's `:transaction/complementos`,
   ordered by `:complemento/sequence`. Returns a CFDI XML string.

   This is the kernel/ADR-025 integration point: complementos stored
   as data are spliced into the envelope at emit time.

   `cfdi-base` is the invoice-element input map MINUS `:cfdi/complementos`."
  [conn tx-eid cfdi-base]
  (let [db (datahike.api/db conn)
        complementos (->> (datahike.api/q
                           '[:find ?seq ?payload ?active
                             :in $ ?t
                             :where
                             [?c :complemento/transaction ?t]
                             [?c :complemento/sequence ?seq]
                             [?c :complemento/payload ?payload]
                             [?c :complemento/active ?active]]
                           db tx-eid)
                          (filter (fn [[_ _ active]] active))
                          (sort-by first)
                          (map second))]
    (emit-string
     (invoice-element (assoc cfdi-base :cfdi/complementos complementos)))))

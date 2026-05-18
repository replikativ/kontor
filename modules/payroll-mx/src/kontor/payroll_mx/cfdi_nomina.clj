(ns kontor.payroll-mx.cfdi-nomina
  "CFDI Nómina 1.2 — Mexican electronic payslip XML builder. ADR-082.

   The CFDI Nómina is a CFDI 4.0 envelope (TipoDeComprobante='N')
   carrying a `<nomina12:Nomina>` complemento. The complemento has
   the structure (per SAT Anexo 20 + Catálogos de Nómina v1.2):

     <nomina12:Nomina Version=\"1.2\" TipoNomina ...>
       <nomina12:Emisor RegistroPatronal=\"...\"/>
       <nomina12:Receptor Curp=\"...\" NumSeguridadSocial=\"...\" .../>
       <nomina12:Percepciones>
         <nomina12:Percepcion TipoPercepcion=\"001\" ...
                              Concepto=\"Sueldos\"
                              ImporteGravado=\"...\" ImporteExento=\"...\"/>
         ...
       </nomina12:Percepciones>
       <nomina12:Deducciones>
         <nomina12:Deduccion TipoDeduccion=\"002\"
                             Concepto=\"ISR retenido\" Importe=\"...\"/>
         ...
       </nomina12:Deducciones>
       <nomina12:OtrosPagos>
         <nomina12:OtroPago TipoOtroPago=\"002\"
                            Concepto=\"Subsidio para el empleo\"
                            Importe=\"...\"/>
       </nomina12:OtrosPagos>
     </nomina12:Nomina>

   We produce the **unsigned** XML; the PAC (Proveedor Autorizado
   de Certificación) stamps it with a TFD (TimbreFiscalDigital) and
   returns the UUID. The PAC integration is partner-side
   (kontor-l10n-mx-pac-*). The kernel records the returned UUID on
   `:audit-doc` with category `:payroll-filing`, language `:es-mx`.

   ## Tipo de nómina (CFDI Nómina TipoNomina)

   - O  Ordinaria (regular pay period)
   - E  Extraordinaria (aguinaldo / PTU / severance)

   ## Periodicidad (PeriodicidadPago — c_PeriodicidadPago)

   - 01 Diario
   - 02 Semanal
   - 03 Catorcenal (every 14 days)
   - 04 Quincenal (twice/month)
   - 05 Mensual
   - 06 Bimestral
   - 07 Unidad obra
   - 08 Comisión
   - 09 Precio alzado
   - 10 Decenal
   - 99 Otra periodicidad

   ## References

   SAT public spec — Anexo 20 v4.0 + Catálogos de Nómina v1.2:
     https://www.sat.gob.mx/cs/Satellite?blobcol=urldata&blobkey=id&blobtable=MungoBlobs&blobwhere=1461174792380

   No proprietary SAT/Anexo-20 text lifted; this implementation is
   independently derived from the public spec."
  (:require [clojure.data.xml :as xml]
            [kontor.payroll-mx.wage-types :as wt])
  (:import [java.math BigDecimal RoundingMode]
           [java.text SimpleDateFormat]
           [java.util Date TimeZone]))

(xml/alias-uri 'cfdi    "http://www.sat.gob.mx/cfd/4")
(xml/alias-uri 'nomina12 "http://www.sat.gob.mx/nomina12")

;; ============================================================================
;; Period vocabulary (c_PeriodicidadPago)
;; ============================================================================

(def periodicidades
  "Periodicidad → SAT c_PeriodicidadPago code."
  {:daily          "01"
   :weekly         "02"
   :biweekly       "03"   ; 14-day
   :semi-monthly   "04"   ; quincenal (twice/month)
   :monthly        "05"
   :bimonthly      "06"
   :decenal        "10"
   :other          "99"})

(def tipos-nomina
  "TipoNomina (CFDI Nómina) — 'O' regular, 'E' extraordinaria."
  {:ordinary       "O"
   :extraordinary  "E"})

;; ============================================================================
;; Format helpers
;; ============================================================================

(defn- fmt-money
  ^String [n]
  (-> ^BigDecimal (bigdec n)
      (.setScale 2 RoundingMode/HALF_EVEN)
      .toPlainString))

(defn- fmt-date
  "CFDI Nómina date: YYYY-MM-DD."
  ^String [^Date d]
  (let [df (doto (SimpleDateFormat. "yyyy-MM-dd")
             (.setTimeZone (TimeZone/getTimeZone "UTC")))]
    (.format df d)))

(defn- days-between
  "Inclusive day count between two #inst values, integer."
  ^long [^Date a ^Date b]
  (let [ms (- (.getTime b) (.getTime a))
        days-frac (/ (double ms) (double (* 1000 60 60 24)))]
    (long (Math/round (+ days-frac 1.0)))))

;; ============================================================================
;; Element builders
;; ============================================================================

(defn- percepcion-element
  "Build one <nomina12:Percepcion> element."
  [{:keys [wage-type amount]}]
  (let [entry (wt/lookup wage-type)
        sat-code (:sat-code entry)
        gravado? (:gravado? entry)
        importe (fmt-money amount)
        importe-grav (if gravado? importe "0.00")
        importe-exen (if gravado? "0.00" importe)]
    (xml/element ::nomina12/Percepcion
                 {:TipoPercepcion sat-code
                  :Clave (str "P" sat-code)
                  :Concepto (:description entry)
                  :ImporteGravado importe-grav
                  :ImporteExento importe-exen})))

(defn- deduccion-element
  "Build one <nomina12:Deduccion> element."
  [{:keys [wage-type amount]}]
  (let [entry (wt/lookup wage-type)
        sat-code (:sat-code entry)]
    (xml/element ::nomina12/Deduccion
                 {:TipoDeduccion sat-code
                  :Clave (str "D" sat-code)
                  :Concepto (:description entry)
                  :Importe (fmt-money amount)})))

(defn- otro-pago-element
  "Build one <nomina12:OtroPago> element."
  [{:keys [wage-type amount]}]
  (let [entry (wt/lookup wage-type)
        sat-code (:sat-code entry)]
    (xml/element ::nomina12/OtroPago
                 {:TipoOtroPago sat-code
                  :Clave (str "O" sat-code)
                  :Concepto (:description entry)
                  :Importe (fmt-money amount)})))

(defn- percepciones-block
  "Build the <nomina12:Percepciones> aggregate element for the worker-
   side percepciones (excludes employer-only rows)."
  [worker-rows]
  (when (seq worker-rows)
    (let [percep (wt/percepciones worker-rows)
          total-grav (->> percep
                          (filter (fn [r] (:gravado? (wt/lookup (:wage-type r)))))
                          (map :amount)
                          (reduce (fnil #(.add ^BigDecimal %1 ^BigDecimal %2)
                                        0M)
                                  0M))
          total-exen (->> percep
                          (remove (fn [r] (:gravado? (wt/lookup (:wage-type r)))))
                          (map :amount)
                          (reduce (fnil #(.add ^BigDecimal %1 ^BigDecimal %2)
                                        0M)
                                  0M))
          total (.add ^BigDecimal total-grav ^BigDecimal total-exen)]
      (when (seq percep)
        (xml/element ::nomina12/Percepciones
                     {:TotalSueldos (fmt-money total)
                      :TotalGravado (fmt-money total-grav)
                      :TotalExento  (fmt-money total-exen)}
                     (mapv percepcion-element percep))))))

(defn- deducciones-block
  [worker-rows]
  (let [deduc (wt/deducciones worker-rows)]
    (when (seq deduc)
      (xml/element ::nomina12/Deducciones
                   {:TotalImpuestosRetenidos
                    (->> deduc
                         (filter (fn [r] (= :isr-retencion (:wage-type r))))
                         (map :amount)
                         (reduce (fnil #(.add ^BigDecimal %1 ^BigDecimal %2) 0M) 0M)
                         fmt-money)
                    :TotalOtrasDeducciones
                    (->> deduc
                         (remove (fn [r] (= :isr-retencion (:wage-type r))))
                         (map :amount)
                         (reduce (fnil #(.add ^BigDecimal %1 ^BigDecimal %2) 0M) 0M)
                         fmt-money)}
                   (mapv deduccion-element deduc)))))

(defn- otros-pagos-block
  [worker-rows]
  (let [otros (wt/otros-pagos worker-rows)]
    (when (seq otros)
      (xml/element ::nomina12/OtrosPagos {}
                   (mapv otro-pago-element otros)))))

;; ============================================================================
;; Nómina complemento envelope
;; ============================================================================

(defn nomina-complemento
  "Build the `<nomina12:Nomina>` complemento element for one
   (employee, period) record.

   Required opts:
     :facts          — one :payroll-facts map
     :employer       — {:rfc :nombre :registro-patronal
                        :curp ;; for personas físicas only
                        :rfc-pat-origen}
     :employee       — {:rfc :curp :nss
                        :tipo-contrato \"01\"
                        :tipo-regimen  \"02\"
                        :tipo-jornada  \"01\"
                        :periodicidad-pago :semi-monthly
                        :salario-base-cot-apor 250.00M
                        :salario-diario  250.00M
                        :clave-ent-fed   \"NLE\"     ; INE state code
                        :antiguedad      \"P5Y\"      ; ISO-8601 duration
                        :fecha-inicio-rel-laboral #inst \"2018-01-15\"
                        :num-empleado   \"E0123\"
                        :nombre         \"...\"}
     :tipo           — :ordinary | :extraordinary

   Optional:
     :version        — defaults to \"1.2\"."
  [{:keys [facts employer employee tipo version]
    :or {version "1.2" tipo :ordinary}}]
  (let [{:keys [period/start period/end period/payment-date wage-types]} facts
        worker-rows (wt/employee-side wage-types)
        percep (wt/percepciones worker-rows)
        deduc  (wt/deducciones worker-rows)
        otros  (wt/otros-pagos worker-rows)
        total-percep (->> percep
                          (map :amount)
                          (reduce (fnil #(.add ^BigDecimal %1 ^BigDecimal %2) 0M) 0M))
        total-deduc (->> deduc
                         (map :amount)
                         (reduce (fnil #(.add ^BigDecimal %1 ^BigDecimal %2) 0M) 0M))
        total-otros (->> otros
                         (map :amount)
                         (reduce (fnil #(.add ^BigDecimal %1 ^BigDecimal %2) 0M) 0M))
        dias-pagados (days-between start end)]
    (xml/element
     ::nomina12/Nomina
     {:Version version
      :TipoNomina (get tipos-nomina tipo)
      :FechaPago  (fmt-date payment-date)
      :FechaInicialPago (fmt-date start)
      :FechaFinalPago   (fmt-date end)
      :NumDiasPagados (str dias-pagados)
      :TotalPercepciones (fmt-money total-percep)
      :TotalDeducciones (fmt-money total-deduc)
      :TotalOtrosPagos (fmt-money total-otros)}
     (xml/element ::nomina12/Emisor
                  (cond-> {:RegistroPatronal (:registro-patronal employer)}
                    (:curp employer) (assoc :Curp (:curp employer))
                    (:rfc-pat-origen employer)
                    (assoc :RfcPatronOrigen (:rfc-pat-origen employer))))
     (xml/element ::nomina12/Receptor
                  (cond-> {:Curp (or (:curp employee) (:employee/curp facts))
                           :TipoContrato (or (:tipo-contrato employee) "01")
                           :TipoRegimen  (or (:tipo-regimen employee) "02")
                           :TipoJornada  (or (:tipo-jornada employee) "01")
                           :NumEmpleado  (or (:num-empleado employee)
                                             (:employee/code facts))
                           :PeriodicidadPago (get periodicidades
                                                  (or (:periodicidad-pago employee)
                                                      :semi-monthly))}
                    (:nss employee) (assoc :NumSeguridadSocial (:nss employee))
                    (:clave-ent-fed employee) (assoc :ClaveEntFed (:clave-ent-fed employee))
                    (:salario-base-cot-apor employee)
                    (assoc :SalarioBaseCotApor (fmt-money (:salario-base-cot-apor employee)))
                    (:salario-diario employee)
                    (assoc :SalarioDiarioIntegrado (fmt-money (:salario-diario employee)))
                    (:antiguedad employee) (assoc :Antigüedad (:antiguedad employee))
                    (:fecha-inicio-rel-laboral employee)
                    (assoc :FechaInicioRelLaboral
                           (fmt-date (:fecha-inicio-rel-laboral employee)))))
     (percepciones-block worker-rows)
     (deducciones-block worker-rows)
     (otros-pagos-block worker-rows))))

(defn emit-complemento-string
  "Serialize a `nomina-complemento` element to canonical XML."
  ^String [elem]
  (xml/emit-str elem))

;; ============================================================================
;; CFDI envelope (TipoDeComprobante=N) — kernel emits, partner signs
;; ============================================================================

(defn nomina-envelope
  "Build a CFDI 4.0 envelope `<cfdi:Comprobante>` with
   TipoDeComprobante='N' and one Nómina complemento. Returns the XML
   string.

   Required opts merge :emit-opts (the CFDI 4.0 base) with :facts
   and :employer / :employee for the complemento."
  [{:keys [facts employer employee tipo
           ;; CFDI 4.0 base
           serie folio fecha lugar-expedicion no-certificado certificado
           moneda]
    :or {tipo :ordinary moneda "MXN"}}]
  (let [complemento (nomina-complemento {:facts facts :employer employer
                                         :employee employee :tipo tipo})
        worker-rows (wt/employee-side (:wage-types facts))
        total-percep (->> (wt/percepciones worker-rows)
                          (map :amount)
                          (reduce (fnil #(.add ^BigDecimal %1 ^BigDecimal %2) 0M) 0M))
        total-deduc (->> (wt/deducciones worker-rows)
                         (map :amount)
                         (reduce (fnil #(.add ^BigDecimal %1 ^BigDecimal %2) 0M) 0M))
        total-otros (->> (wt/otros-pagos worker-rows)
                         (map :amount)
                         (reduce (fnil #(.add ^BigDecimal %1 ^BigDecimal %2) 0M) 0M))
        neto (-> ^BigDecimal total-percep
                 (.subtract ^BigDecimal total-deduc)
                 (.add ^BigDecimal total-otros)
                 (.setScale 2 RoundingMode/HALF_EVEN))
        envelope
        (xml/element
         ::cfdi/Comprobante
         (cond-> {:Version "4.0"
                  :Fecha (let [df (doto (SimpleDateFormat. "yyyy-MM-dd'T'HH:mm:ss")
                                    (.setTimeZone (TimeZone/getTimeZone "UTC")))]
                           (.format df ^Date (or fecha (:period/payment-date facts))))
                  :NoCertificado no-certificado
                  :Certificado   certificado
                  :SubTotal      (fmt-money total-percep)
                  :Descuento     (fmt-money (.add ^BigDecimal total-deduc 0M))
                  :Moneda        moneda
                  :Total         (fmt-money neto)
                  :TipoDeComprobante "N"
                  :Exportacion   "01"
                  :MetodoPago    "PUE"
                  :LugarExpedicion lugar-expedicion
                  :Sello         ""}
           serie (assoc :Serie serie)
           folio (assoc :Folio folio))
         (xml/element ::cfdi/Emisor
                      {:Rfc (:rfc employer)
                       :Nombre (:nombre employer)
                       :RegimenFiscal (or (:regimen-fiscal employer) "601")})
         (xml/element ::cfdi/Receptor
                      {:Rfc (:rfc employee)
                       :Nombre (or (:nombre employee)
                                   (:employee/name facts))
                       :DomicilioFiscalReceptor (or (:domicilio-fiscal employee) "00000")
                       :RegimenFiscalReceptor   (or (:regimen-fiscal-receptor employee)
                                                    "605")
                       :UsoCFDI                 "CN01"})
         (xml/element ::cfdi/Conceptos {}
                      (xml/element
                       ::cfdi/Concepto
                       {:ClaveProdServ "84111505"   ; Servicios de nómina (SAT catálogo)
                        :Cantidad "1"
                        :ClaveUnidad "ACT"
                        :Descripcion "Pago de nómina"
                        :ValorUnitario (fmt-money total-percep)
                        :Importe (fmt-money total-percep)
                        :Descuento (fmt-money total-deduc)
                        :ObjetoImp "01"}))
         (xml/element ::cfdi/Complemento {} complemento))]
    (xml/emit-str envelope)))

(ns kontor.payroll-mx.wage-types
  "Canonical MX wage-type vocabulary + SAT Catálogo c_TipoPercepcion /
   c_TipoDeduccion / c_TipoOtroPago code mapping per Anexo 20 (CFDI
   Nómina 1.2).

   Each canonical kontor wage-type maps to:
     - a CFDI Nómina **kind** — :percepcion | :deduccion | :otro-pago
     - the SAT type code (c_TipoPercepcion / c_TipoDeduccion / c_TipoOtroPago)
     - the SAT Código Agrupador account number it routes to in the GL.

   This is **vocabulary**, not a tax-table. Rates / brackets live in
   the consumer's payroll engine (CONTPAQi, Aspel, etc.). The kernel
   never bundles rates.

   References (public SAT specs):
     https://www.sat.gob.mx/cs/Satellite?blobcol=urldata&blobkey=id&blobtable=MungoBlobs&blobwhere=1461174792380
     Catálogos de Nómina v1.2 (xls) on sat.gob.mx.")

;; ============================================================================
;; SAT Nómina 1.2 kind partitions
;; ============================================================================

(def kinds
  "Top-level partition of a CFDI Nómina row.

     :percepcion — earnings paid to the worker (Sueldo, Aguinaldo,
                   Horas Extra, …)
     :deduccion  — withheld from the worker (ISR, IMSS trabajador,
                   INFONAVIT)
     :otro-pago  — special non-taxable / refundable items
                   (Subsidio al empleo)"
  #{:percepcion :deduccion :otro-pago})

;; ============================================================================
;; The canonical wage-type registry
;;
;; Each entry binds a kontor keyword to:
;;   :sat-code      — SAT catálogo code (c_TipoPercepcion or
;;                    c_TipoDeduccion or c_TipoOtroPago) per Anexo 20
;;   :kind          — :percepcion | :deduccion | :otro-pago
;;   :codigo-agrup  — SAT Código Agrupador chart-of-accounts code
;;   :gravado?      — taxable for ISR (informational; the consumer's
;;                    engine separates Gravado/Exento)
;;   :description   — Spanish (es-mx) display label
;; ============================================================================

(def registry
  "Canonical MX wage-type registry. See ADR-082 + sat.gob.mx Anexo 20."
  {;; ----- Percepciones -----
   :sueldo              {:sat-code "001"
                         :kind :percepcion
                         :codigo-agrup "601.01"
                         :gravado? true
                         :description "Sueldos, Salarios Rayas y Jornales"}

   :hora-extra-doble    {:sat-code "019"
                         :kind :percepcion
                         :codigo-agrup "601.01"
                         :gravado? true
                         :description "Horas extra dobles"}

   :hora-extra-triple   {:sat-code "019"
                         :kind :percepcion
                         :codigo-agrup "601.01"
                         :gravado? true
                         :description "Horas extra triples"}

   :aguinaldo           {:sat-code "002"
                         :kind :percepcion
                         :codigo-agrup "601.02"
                         :gravado? true
                         :description "Gratificación Anual (Aguinaldo)"}

   :prima-vacacional    {:sat-code "021"
                         :kind :percepcion
                         :codigo-agrup "601.02"
                         :gravado? true
                         :description "Prima Vacacional"}

   :vales-de-despensa   {:sat-code "029"
                         :kind :percepcion
                         :codigo-agrup "601.84"
                         :gravado? false
                         :description "Vales de despensa"}

   :fondo-de-ahorro     {:sat-code "005"
                         :kind :percepcion
                         :codigo-agrup "601.84"
                         :gravado? false
                         :description "Aportaciones al Fondo de Ahorro"}

   ;; ----- Deducciones (withheld from worker) -----
   :isr-retencion       {:sat-code "002"
                         :kind :deduccion
                         :codigo-agrup "206.04"
                         :gravado? false
                         :description "ISR retenido"}

   :imss-trabajador     {:sat-code "001"
                         :kind :deduccion
                         :codigo-agrup "206.05"
                         :gravado? false
                         :description "Cuotas IMSS — parte trabajador"}

   :infonavit-trabajador {:sat-code "010"
                          :kind :deduccion
                          :codigo-agrup "206.06"
                          :gravado? false
                          :description "Crédito INFONAVIT"}

   ;; ----- Employer-paid (recorded as employer expense + liability) -----
   ;; These rows do NOT appear on the worker's pay slip as a deduction;
   ;; the employer carries them. CFDI Nómina records them on
   ;; "OtrosPagos" only when separable; otherwise the kernel records
   ;; them as a side journal (Dr 601.05 / Cr 206.05).
   :imss-patron         {:sat-code nil          ; employer-only — not on CFDI worker side
                         :kind :percepcion      ; recorded as employer expense
                         :codigo-agrup "601.05"
                         :gravado? false
                         :employer-only? true
                         :description "Cuotas IMSS — parte patronal"}

   :infonavit-patron    {:sat-code nil
                         :kind :percepcion
                         :codigo-agrup "601.06"
                         :gravado? false
                         :employer-only? true
                         :description "Aportaciones patronales INFONAVIT"}

   :rcv-patron          {:sat-code nil
                         :kind :percepcion
                         :codigo-agrup "601.05"
                         :gravado? false
                         :employer-only? true
                         :description "Retiro, Cesantía y Vejez (RCV) — patrón"}

   ;; ----- Otros pagos -----
   :subsidio-al-empleo  {:sat-code "002"
                         :kind :otro-pago
                         :codigo-agrup "206.04"   ; reduces ISR liability
                         :gravado? false
                         :description "Subsidio para el empleo"}})

(def known-codes
  "Set of supported kontor wage-type keywords."
  (set (keys registry)))

(defn lookup
  "Look up a wage-type's registry entry. Throws if unknown."
  [wage-type]
  (or (get registry wage-type)
      (throw (ex-info "Unknown MX wage-type"
                      {:wage-type wage-type
                       :known known-codes}))))

(defn employer-only?
  "True if this wage-type is an employer-only expense (does NOT appear
   on the worker's CFDI as a deduction; recorded only in the GL)."
  [wage-type]
  (boolean (:employer-only? (lookup wage-type))))

(defn kind
  "Return :percepcion / :deduccion / :otro-pago for a wage-type."
  [wage-type]
  (:kind (lookup wage-type)))

(defn sat-code
  "Return the SAT catálogo code for a wage-type (c_TipoPercepcion /
   c_TipoDeduccion / c_TipoOtroPago)."
  [wage-type]
  (:sat-code (lookup wage-type)))

(defn codigo-agrupador
  "Return the SAT Código Agrupador chart-of-accounts code for a
   wage-type."
  [wage-type]
  (:codigo-agrup (lookup wage-type)))

(defn description
  "Spanish (es-mx) human-readable description for a wage-type."
  [wage-type]
  (:description (lookup wage-type)))

(defn percepciones
  "Filter `wage-types` to `:percepcion` rows."
  [wage-types]
  (filterv #(= :percepcion (kind (:wage-type %))) wage-types))

(defn deducciones
  "Filter `wage-types` to `:deduccion` rows."
  [wage-types]
  (filterv #(= :deduccion (kind (:wage-type %))) wage-types))

(defn otros-pagos
  "Filter `wage-types` to `:otro-pago` rows."
  [wage-types]
  (filterv #(= :otro-pago (kind (:wage-type %))) wage-types))

(defn employee-side
  "Filter wage-types to those that show on the worker's CFDI Nómina
   (excludes employer-only rows like IMSS patrón)."
  [wage-types]
  (filterv #(not (employer-only? (:wage-type %))) wage-types))

(defn employer-side
  "Filter wage-types to employer-only rows (IMSS patrón / INFONAVIT
   patrón / RCV patrón) — these go to the GL but NOT to the CFDI
   worker-side bloque."
  [wage-types]
  (filterv #(employer-only? (:wage-type %)) wage-types))

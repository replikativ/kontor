(ns kontor.payroll-mx.compute
  "Compute-provider implementations for MX payroll engines.

   The kernel does NOT compute payroll — these adapters ingest the
   vendor file format and project it into the canonical
   `:payroll-facts` shape.

   - `ContpaqiNominasProvider` — CSV export from CONTPAQi Nóminas
     (dominant mid-market SMB engine). The product also exports
     XLSX; we use CSV for the unit-test surface since XLSX would
     drag in a heavy parser. The XLSX flow is consumer-side; the
     :wage-type map is identical.

   - `AspelNoiProvider` — CSV export from Aspel NOI (the other
     dominant SMB payroll engine).

   ## Column maps

   Each provider carries a `code → :wage-type` map keyed by the
   vendor's internal concept code. The maps are illustrative
   defaults — real customers customize them per engine
   configuration, so the providers accept `:code-map` opts at
   construction time.

   ## CSV schema (CONTPAQi default)
     RFC,CURP,EmpleadoCod,FechaInicio,FechaFin,FechaPago,
     ConceptoCod,Importe

   ## CSV schema (Aspel NOI default)
     codigo_emp,rfc,curp,periodo_ini,periodo_fin,fecha_pago,
     codigo_concepto,monto

   The vendor docs publish full column lists; we map only the
   load-bearing ones for v1.0."
  (:require [clojure.data.csv :as csv]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [kontor.payroll-mx.core :as core])
  (:import [java.math BigDecimal]
           [java.text SimpleDateFormat]
           [java.util Date]))

;; ============================================================================
;; Helpers
;; ============================================================================

(defn- parse-date
  "Parse YYYY-MM-DD into a java.util.Date at 00:00 UTC."
  ^Date [^String s]
  (when (and s (not (str/blank? s)))
    (.parse (doto (SimpleDateFormat. "yyyy-MM-dd")
              (.setTimeZone (java.util.TimeZone/getTimeZone "UTC")))
            s)))

(defn- parse-amount
  "Parse a string into BigDecimal HALF-EVEN."
  ^BigDecimal [s]
  (-> ^String s str/trim BigDecimal. (.setScale 2 java.math.RoundingMode/HALF_EVEN)))

(defn- group-period-rows
  "Group flat rows by (rfc, period-start, period-end) into one record per
   (employee, period)."
  [rows]
  (->> rows
       (group-by (juxt :employee/rfc :period/start :period/end))
       (mapv (fn [[[rfc start end] grp]]
               (let [first-row (first grp)]
                 (core/make-payroll-facts
                  {:employee/rfc rfc
                   :employee/curp (:employee/curp first-row)
                   :employee/code (:employee/code first-row)
                   :period/start start
                   :period/end end
                   :period/payment-date (:period/payment-date first-row)
                   :wage-types (mapv (fn [r]
                                       {:wage-type (:wage-type r)
                                        :amount (:amount r)
                                        :commodity "MXN"})
                                     grp)}))))))

;; ============================================================================
;; CONTPAQi default concept-code map
;;
;; Real installs override via :code-map opts.
;; ============================================================================

(def contpaqi-default-code-map
  "Default mapping from CONTPAQi Nóminas concepto code → kontor
   wage-type. Reflects the out-of-the-box CONTPAQi catalog; real
   installs commonly remap."
  {"P001" :sueldo
   "P002" :aguinaldo
   "P019" :hora-extra-doble
   "P020" :hora-extra-triple
   "P021" :prima-vacacional
   "P029" :vales-de-despensa
   "P005" :fondo-de-ahorro
   "D002" :isr-retencion
   "D001" :imss-trabajador
   "D010" :infonavit-trabajador
   "O002" :subsidio-al-empleo
   ;; Employer-paid sidecars
   "E001" :imss-patron
   "E002" :infonavit-patron
   "E003" :rcv-patron})

(def aspel-default-code-map
  "Default mapping from Aspel NOI codigo_concepto → kontor wage-type."
  {"100" :sueldo
   "110" :hora-extra-doble
   "111" :hora-extra-triple
   "120" :aguinaldo
   "121" :prima-vacacional
   "130" :vales-de-despensa
   "131" :fondo-de-ahorro
   "200" :isr-retencion
   "210" :imss-trabajador
   "220" :infonavit-trabajador
   "300" :subsidio-al-empleo
   "400" :imss-patron
   "410" :infonavit-patron
   "420" :rcv-patron})

;; ============================================================================
;; ContpaqiNominasProvider
;; ============================================================================

(defrecord ContpaqiNominasProvider [code-map]
  core/PayrollComputeProvider
  (vendor-id [_] :contpaqi-nominas)
  (parse-period [_ source]
    (with-open [r (io/reader source)]
      (let [[header & rows] (csv/read-csv r)
            ;; Tolerate trailing whitespace / BOM
            header (mapv str/trim header)
            idx (zipmap header (range))
            col (fn [row k] (nth row (get idx k) nil))
            parsed
            (for [row rows
                  :when (seq row)
                  :let [vendor-code (col row "ConceptoCod")
                        wt (get code-map vendor-code)]
                  :when wt]
              {:employee/rfc        (col row "RFC")
               :employee/curp       (col row "CURP")
               :employee/code       (col row "EmpleadoCod")
               :period/start        (parse-date (col row "FechaInicio"))
               :period/end          (parse-date (col row "FechaFin"))
               :period/payment-date (parse-date (col row "FechaPago"))
               :wage-type           wt
               :amount              (parse-amount (col row "Importe"))})]
        (group-period-rows parsed)))))

(defn make-contpaqi-nominas-provider
  "Construct a ContpaqiNominasProvider. Pass `:code-map` to override
   `contpaqi-default-code-map`. The default is merged with the
   provided map (the supplied entries win); pass `:replace-map true`
   together with `:code-map` to use ONLY the supplied map (drops the
   defaults — useful when a customer's vendor catalog has no overlap
   with the OOTB CONTPAQi codes)."
  ([] (make-contpaqi-nominas-provider {}))
  ([{:keys [code-map replace-map]}]
   (->ContpaqiNominasProvider
    (if replace-map
      (or code-map {})
      (merge contpaqi-default-code-map code-map)))))

;; ============================================================================
;; AspelNoiProvider
;; ============================================================================

(defrecord AspelNoiProvider [code-map]
  core/PayrollComputeProvider
  (vendor-id [_] :aspel-noi)
  (parse-period [_ source]
    (with-open [r (io/reader source)]
      (let [[header & rows] (csv/read-csv r)
            header (mapv str/trim header)
            idx (zipmap header (range))
            col (fn [row k] (nth row (get idx k) nil))
            parsed
            (for [row rows
                  :when (seq row)
                  :let [vendor-code (col row "codigo_concepto")
                        wt (get code-map vendor-code)]
                  :when wt]
              {:employee/rfc        (col row "rfc")
               :employee/curp       (col row "curp")
               :employee/code       (col row "codigo_emp")
               :period/start        (parse-date (col row "periodo_ini"))
               :period/end          (parse-date (col row "periodo_fin"))
               :period/payment-date (parse-date (col row "fecha_pago"))
               :wage-type           wt
               :amount              (parse-amount (col row "monto"))})]
        (group-period-rows parsed)))))

(defn make-aspel-noi-provider
  "Construct an AspelNoiProvider. See
   `make-contpaqi-nominas-provider` for `:code-map` + `:replace-map`
   semantics."
  ([] (make-aspel-noi-provider {}))
  ([{:keys [code-map replace-map]}]
   (->AspelNoiProvider
    (if replace-map
      (or code-map {})
      (merge aspel-default-code-map code-map)))))

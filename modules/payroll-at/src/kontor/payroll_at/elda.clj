(ns kontor.payroll-at.elda
  "ELDA (Elektronischer Datenaustausch mit den österreichischen
   Sozialversicherungsträgern) file-format builder.

   The kernel module ships v1 XML emit. Two consumer wire formats:
     - ELDA XML against the Dachverband mBGM XSD — the modern path.
     - ELDA fixed-width DGS text — legacy; not implemented in v1
       (the file pre-2019 carried L16/L19 quarterly + monthly mix;
       the modern adapter targets mBGM only).

   Public references:
     - Dachverband der Sozialversicherungen mBGM-XSD (1.10+).
     - ELDA-Software-Client documentation (public).

   Per ADR-072 the kernel does NOT bundle ÖGK credentials or
   transmit() to the ELDA endpoint. The adapter produces the
   *bytes*; the consumer's worker calls (e.g.) curl / the ELDA-
   Software-Client to upload."
  (:require [clojure.data.xml :as xml]
            [clojure.string :as str])
  (:import [java.io ByteArrayOutputStream]
           [java.math BigDecimal RoundingMode]
           [java.text SimpleDateFormat]
           [java.util Date TimeZone]))

;; ============================================================================
;; Date format
;; ============================================================================

(defn- ymd ^String [^Date d]
  (let [fmt (doto (SimpleDateFormat. "yyyy-MM-dd")
              (.setTimeZone (TimeZone/getTimeZone "UTC")))]
    (.format fmt d)))

(defn- ym ^String [^Date d]
  (let [fmt (doto (SimpleDateFormat. "yyyy-MM")
              (.setTimeZone (TimeZone/getTimeZone "UTC")))]
    (.format fmt d)))

;; ============================================================================
;; Validation
;; ============================================================================

(def ^:private vsnr-pattern #"^\d{10}$")

(defn- validate-employee
  [{:keys [vsnr name] :as emp}]
  (when (str/blank? vsnr)
    (throw (ex-info "mBGM employee row missing :vsnr"
                    {:employee emp})))
  (when-not (re-matches vsnr-pattern (str vsnr))
    (throw (ex-info "mBGM employee :vsnr must be 10 digits"
                    {:vsnr vsnr :name name :hint "Austrian VSNR is 10 digits"})))
  emp)

;; ============================================================================
;; Amount rendering
;; ============================================================================

(defn- ^String fmt-amt
  "Format a BigDecimal for ELDA XML — '.' as decimal separator,
   exactly two fractional digits."
  [x]
  (let [^BigDecimal bd (if (instance? BigDecimal x)
                         x
                         (BigDecimal/valueOf (long (or x 0))))]
    (.toPlainString (.setScale bd 2 RoundingMode/HALF_EVEN))))

;; ============================================================================
;; XML construction
;; ============================================================================

(defn- emp->element
  "Render one employee row in mBGM XML shape."
  [{:keys [vsnr name beitragsgruppe line-items] :as emp}]
  (validate-employee emp)
  (let [pull (fn [wt]
               (->> line-items
                    (filter #(= wt (:wage-type %)))
                    (map :amount)
                    (reduce (fn [^BigDecimal a ^BigDecimal b] (.add a b)) 0M)))
        grundgehalt        (pull :grundgehalt)
        überstunden        (pull :überstunden)
        sonderzahlung      (.add ^BigDecimal (pull :urlaubsremuneration)
                                 ^BigDecimal (pull :weihnachtsremuneration))
        ;; Beitragsgrundlage = (regular gross + überstunden), Sonderzahlung
        ;; is reported separately so the SV cap applies correctly.
        beitragsgrundlage  (.add ^BigDecimal grundgehalt
                                 ^BigDecimal überstunden)]
    (xml/element
     :Person {}
     (xml/element :VSNR {} vsnr)
     (xml/element :Name {} (str name))
     (xml/element :Beitragsgruppe {} (or beitragsgruppe "D1"))
     (xml/element :Beitragsgrundlage {} (fmt-amt beitragsgrundlage))
     (xml/element :Sonderzahlung {} (fmt-amt sonderzahlung))
     (xml/element :AN-SV-Anteil {} (fmt-amt (pull :sv-arbeitnehmer)))
     (xml/element :AG-SV-Anteil {} (fmt-amt (pull :sv-arbeitgeber))))))

(defn build-mbgm-element
  "Build the root XML element for an mBGM submission. Does NOT serialize."
  [{:keys [dienstgeber-beitragskonto employer-name period employees]}]
  (when (str/blank? dienstgeber-beitragskonto)
    (throw (ex-info "mBGM requires :dienstgeber-beitragskonto" {})))
  (when-not (and period (:from period))
    (throw (ex-info "mBGM requires :period {:from <Date>}" {})))
  (when (empty? employees)
    (throw (ex-info "mBGM requires :employees (non-empty)" {})))
  (xml/element
   :mBGM {:xmlns "urn:eu:europa:sozialversicherung:mbgm:v1"
          :Version "1.10"}
   (xml/element :Header {}
                (xml/element :Beitragskonto {} dienstgeber-beitragskonto)
                (xml/element :Beitragsmonat {} (ym (:from period)))
                (xml/element :Erstellt {} (ymd (Date.))))
   (when employer-name
     (xml/element :Dienstgeber {} (xml/element :Name {} employer-name)))
   (apply xml/element :Personen {}
          (mapv emp->element employees))))

(defn emit-mbgm-xml
  "Emit the mBGM XML payload as a UTF-8 byte array. Pure — no IO.

   Required keys:
     :dienstgeber-beitragskonto — employer's ÖGK Beitragskonto-Nr (string)
     :period                    — {:from <Date> :to <Date>}
     :employees                 — vector of normalized employee maps
                                  (the same shape :payroll-result/employees
                                   uses; see kontor.payroll-at.compute)

   Optional:
     :employer-name             — string"
  ^bytes [opts]
  (let [el (build-mbgm-element opts)
        baos (ByteArrayOutputStream.)]
    (with-open [w (java.io.OutputStreamWriter. baos "UTF-8")]
      (xml/emit el w))
    (.toByteArray baos)))

(defn emit-mbgm-string
  "Convenience: emit-mbgm-xml as a UTF-8 string. Useful in tests."
  ^String [opts]
  (String. (emit-mbgm-xml opts) "UTF-8"))

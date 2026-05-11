(ns datahike-accounting.einvoice-de.factur-x
  "Mustang wrapper — generate XRechnung 3.0 / Factur-X / ZUGFeRD XML
   from a plain-Clojure invoice map (see `invoice.clj`), and embed
   that XML into a PDF/A-3 to produce a Factur-X PDF.

   Why these formats matter (DE/FR e-invoicing):

     XRechnung — German B2G (business-to-government) standard since
       2020. Pure XML; mandatory for invoices to federal/state agencies.
       Profile: 'urn:cen.eu:en16931:2017#compliant#urn:xeinkauf.de:
                 kosit:xrechnung_3.0'
       Conformant to EN 16931 (the European semantic invoice model).

     Factur-X (DE: ZUGFeRD 2.x) — hybrid PDF/A-3 with embedded XML.
       Mandatory for German B2B from 2025-01-01 (Wachstumschancengesetz),
       phased-in receive obligation 2025, send obligation 2027.
       France: Factur-X is the default e-invoice carrier for the
       Chorus Pro / partner platform reform.

     EN 16931 — the European standard underlying both. Mustang produces
       valid EN-16931-conformant XML at the BASIC, EN16931 (BASIC-WL +
       EN16931 conformance), and EXTENDED profiles.

   Mustang itself is APL-2; this wrapper exposes only the bits we
   need. To pull in only the kernel (no Mustang), drop the
   org.mustangproject/library dep from deps.edn — `factur-x.clj`
   will fail to compile but `invoice.clj` won't.

   Profiles supported via `:profile` keyword:
     :xrechnung      — XRechnung 3.0 (DE B2G default)
     :en16931        — Factur-X EN16931 (DE/FR B2B default)
     :basic          — Factur-X BASIC (subset of EN16931)
     :basic-wl       — Factur-X BASIC-WL (without lines)
     :minimum        — Factur-X MINIMUM
     :extended       — Factur-X EXTENDED"
  (:require [datahike-accounting.einvoice-de.invoice :as inv]
            [clojure.string :as str])
  (:import [java.math BigDecimal]
           [java.util Date]
           [org.mustangproject Invoice Item Product TradeParty
            BankDetails Contact ReferencedDocument]
           [org.mustangproject.ZUGFeRD ZUGFeRD2PullProvider Profiles
            ZUGFeRDExporterFromA3]))

;; ============================================================================
;; Profile lookup
;; ============================================================================

(def profile-keys
  "Map our profile keyword → Mustang profile name string."
  {:xrechnung "XRECHNUNG"
   :en16931   "EN16931"
   :basic     "BASIC"
   :basic-wl  "BASICWL"
   :minimum   "MINIMUM"
   :extended  "EXTENDED"})

(defn- ^org.mustangproject.ZUGFeRD.Profile resolve-profile [profile-kw]
  (let [name (or (profile-keys profile-kw)
                 (throw (ex-info (str "Unknown profile: " profile-kw)
                                 {:type :einvoice/unknown-profile
                                  :profile profile-kw
                                  :supported (set (keys profile-keys))})))]
    (Profiles/getByName ^String name)))

;; ============================================================================
;; Clojure-map → Mustang-Java translation
;; ============================================================================

(defn- ^TradeParty party->mustang
  [{:party/keys [name street zip city country vat-id tax-id email contact-name]}]
  (let [tp (TradeParty.)]
    (when name           (.setName tp ^String name))
    (when street         (.setStreet tp ^String street))
    (when zip            (.setZIP tp ^String zip))
    (when city           (.setLocation tp ^String city))
    (when country        (.setCountry tp ^String country))
    (when vat-id         (.addVATID tp ^String vat-id))
    (when tax-id         (.addTaxID tp ^String tax-id))
    (when (or email contact-name)
      (let [c (Contact. (or contact-name "Contact") (or email "") "")]
        (when email (.setEMail c ^String email))
        (.setContact tp c)))
    tp))

(defn- ^Item item->mustang
  [{:item/keys [name description quantity unit-code unit-price vat-rate vat-category]
    :or {unit-code "EA" vat-category "S"}}]
  (let [product (Product. ^String name
                          ^String (or description "")
                          ^String unit-code
                          (bigdec vat-rate))]
    (.setTaxCategoryCode product ^String vat-category)
    (Item. product (bigdec unit-price) (bigdec quantity))))

(defn- ->mustang-date
  ^Date [d]
  (cond
    (nil? d)            nil
    (instance? Date d)  d
    (instance? java.time.Instant d)
    (Date/from ^java.time.Instant d)
    (instance? java.time.LocalDate d)
    (Date/from (.toInstant (.atStartOfDay ^java.time.LocalDate d
                                          (java.time.ZoneOffset/UTC))))
    :else
    (throw (ex-info (str "Cannot coerce to Date: " (class d) " = " d)
                    {:value d}))))

(defn- ^Invoice invoice->mustang
  "Translate a validated invoice-map to a Mustang Invoice object."
  [{:invoice/keys [number issue-date due-date delivery-date currency seller buyer
                   items payment notes payment-terms reference] :as inv}]
  (let [invoice (Invoice.)]
    (.setNumber invoice ^String number)
    (.setIssueDate invoice (->mustang-date issue-date))
    (when due-date     (.setDueDate invoice (->mustang-date due-date)))
    (when delivery-date(.setDeliveryDate invoice (->mustang-date delivery-date)))
    (when currency     (.setCurrency invoice ^String currency))
    (.setSender invoice (party->mustang seller))
    (.setRecipient invoice (party->mustang buyer))
    (when reference    (.setReferenceNumber invoice ^String reference))
    (when payment-terms(.setPaymentTermDescription invoice ^String payment-terms))
    (doseq [n (or notes [])] (.addNote invoice ^String n))
    (when-let [{:payment/keys [iban bic account-name]} payment]
      (let [bd (BankDetails. ^String iban)]
        (when bic          (.setBIC bd ^String bic))
        (when account-name (.setAccountName bd ^String account-name))
        (.. invoice getSender (addBankDetails bd))))
    (doseq [it items] (.addItem invoice (item->mustang it)))
    invoice))

;; ============================================================================
;; Public — XML generation
;; ============================================================================

(defn ^bytes generate-xml
  "Generate the Cross Industry Invoice XML bytes for `invoice` at the
   given `profile-kw` (default :en16931). Returns UTF-8 bytes that can
   be written to .xml or embedded into a PDF.

   Validates the invoice first; throws ex-info on shape errors before
   any Mustang call."
  ([invoice]
   (generate-xml invoice :en16931))
  ([invoice profile-kw]
   (inv/validate! invoice)
   (let [provider (ZUGFeRD2PullProvider.)
         _ (.setProfile provider (resolve-profile profile-kw))
         _ (.generateXML provider (invoice->mustang invoice))]
     (.getXML provider))))

(defn ^String generate-xml-string
  "String form of `generate-xml` — UTF-8 decoded XML. Convenient for
   tests and human inspection; for binary embedding use `generate-xml`."
  ([invoice]
   (generate-xml-string invoice :en16931))
  ([invoice profile-kw]
   (String. ^bytes (generate-xml invoice profile-kw) "UTF-8")))

;; ============================================================================
;; Public — PDF/A-3 embedding (Factur-X)
;; ============================================================================

(defn embed-into-pdf!
  "Embed the EN16931/Factur-X XML for `invoice` into the PDF/A-3 at
   `pdf-input-path` and write the resulting Factur-X PDF to
   `pdf-output-path`. Uses :en16931 profile by default.

   Note: the input PDF must already be PDF/A-3 conformant — Mustang
   does not auto-convert non-conformant PDFs. For typical invoice
   workflows generate the visual PDF with iText / OpenPDF / Apache
   PDFBox at PDF/A-3 conformance, then call this to add the XML
   payload."
  ([invoice ^String pdf-input-path ^String pdf-output-path]
   (embed-into-pdf! invoice pdf-input-path pdf-output-path :en16931))
  ([invoice ^String pdf-input-path ^String pdf-output-path profile-kw]
   (inv/validate! invoice)
   (with-open [exporter (-> (ZUGFeRDExporterFromA3.)
                            (.load pdf-input-path)
                            (.setProfile (resolve-profile profile-kw)))]
     (.setTransaction exporter (invoice->mustang invoice))
     (.export exporter pdf-output-path))))

;; ============================================================================
;; Convenience — write XML to file
;; ============================================================================

(defn write-xml!
  ([invoice path] (write-xml! invoice path :en16931))
  ([invoice path profile-kw]
   (let [bs (generate-xml invoice profile-kw)]
     (with-open [out (java.io.FileOutputStream. ^String path)]
       (.write out ^bytes bs)))))

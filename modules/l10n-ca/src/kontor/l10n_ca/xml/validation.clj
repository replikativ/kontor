(ns kontor.l10n-ca.xml.validation
  "JAXP-based XML Schema (XSD) validation for CRA info-return output.

   Why: the CRA Internet File Transfer pipeline validates submissions
   against the published XSDs. Catching schema violations locally before
   transmission is the only check that *guarantees* CRA's intake will
   accept the file. Adds zero new deps — `javax.xml.validation` ships
   with the JVM.

   Per ADR-015, validation lives in the renderer ring (downstream of
   the kernel). Calling code (T4/T5/T5018 emit fns) opts in via
   `validate!` after producing the XML string.

   When the CRA XSD bundle is downloaded into
   `modules/l10n-ca/test/resources/cra/info-returns-xsd-2026/`, point
   `validate!` at the relevant per-form file (e.g. `T619_T4.xsd`)."
  (:import [javax.xml XMLConstants]
           [javax.xml.transform.stream StreamSource]
           [javax.xml.validation SchemaFactory Validator]
           [java.io File StringReader]
           [org.xml.sax ErrorHandler SAXParseException]))

(defn- record! [acc severity ^SAXParseException e]
  (swap! acc conj {:severity severity
                   :message  (.getMessage e)
                   :line     (.getLineNumber e)
                   :column   (.getColumnNumber e)})
  nil)

(defn- collecting-handler
  "An ErrorHandler that accumulates warnings, errors, and fatal errors
   into an atom-held vector instead of throwing immediately."
  ^ErrorHandler [acc]
  (proxy [Object ErrorHandler] []
    (warning [e]    (record! acc :warning e))
    (error [e]      (record! acc :error e))
    (fatalError [e] (record! acc :fatal e))))

(defn validate
  "Validate `xml-string` against the XSD at `xsd-path`. Does NOT throw.

   Returns {:valid? bool :errors [{:severity :line :column :message} …]}.

   `xsd-path` may be a String, java.io.File, or any other type the
   SchemaFactory's `newSchema` accepts."
  [xsd-path xml-string]
  (let [factory  (SchemaFactory/newInstance
                  XMLConstants/W3C_XML_SCHEMA_NS_URI)
        xsd-file (if (instance? File xsd-path)
                   xsd-path
                   (File. ^String (str xsd-path)))
        schema   (.newSchema factory xsd-file)
        validator (.newValidator schema)
        errors   (atom [])
        _        (.setErrorHandler validator (collecting-handler errors))
        source   (StreamSource. (StringReader. ^String xml-string))]
    (try
      (.validate validator source)
      (catch SAXParseException e
        (swap! errors conj {:severity :fatal
                            :message  (.getMessage e)
                            :line     (.getLineNumber e)
                            :column   (.getColumnNumber e)})))
    (let [errs @errors
          fatal-or-err? (some #(#{:error :fatal} (:severity %)) errs)]
      {:valid?  (not fatal-or-err?)
       :errors  errs})))

(defn validate!
  "Validate `xml-string` against `xsd-path`. Throws ex-info on
   validation failure; returns the input xml-string on success
   (useful for chaining with `(-> submission emit-string (validate! xsd))`)."
  [xml-string xsd-path]
  (let [{:keys [valid? errors]} (validate xsd-path xml-string)]
    (if valid?
      xml-string
      (throw (ex-info "XML failed XSD validation"
                      {:type     :xsd-validation-failed
                       :xsd-path (str xsd-path)
                       :errors   errors})))))

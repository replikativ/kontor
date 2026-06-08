(ns kontor.l10n-ca.pdf
  "PDF renderer — would fill CRA fillable PDFs for paper filing, but…

   **IMPORTANT (discovered 2026-05-10 against real CRA PDFs):** the
   CRA fillable PDFs (5000-R T1, 5010-C BC428, T2125, etc.) are
   **XFA dynamic forms** (Adobe LiveCycle / AEM), NOT classic
   AcroForm PDFs. PDFBox's `getAcroForm().getFields()` returns only
   a single container field `form1[0]` per file; the real fields
   live in an XFA XML stream addressable by SOM expressions like
   `form1.Page1.PartA.Line_15000.Line_15000_Amount`. The XFA XML
   for T1 2024 has 1055 field names; for BC428, fields are named
   generically (`Amount`, `Amount1`) and disambiguated by the
   enclosing subform.

   The `fill-form` function below works for **classic AcroForm
   templates** (useful for non-CRA PDFs that consume the same field
   map shape) but does NOT fill CRA's XFA forms.

   ## Paths forward (not implemented in this slice)

   1. **XFA modification via PDFBox**: PDFBox has limited XFA support
      (`PDAcroForm.getXFA()` returns the XFA XML; we'd parse, modify
      individual `<field>` values via SOM expression matching, and
      write back). Doable but non-trivial; needs XPath-style traversal.
   2. **External tool**: shell out to a tool that natively understands
      XFA (Adobe Acrobat, iText's XFA module — but iText 7+'s XFA
      support is in the AGPL/commercial tier).
   3. **PDF overlay**: skip the fillable form entirely and overlay
      typed text at hardcoded coordinates over a flat scan of the
      form. Brittle but tractable.
   4. **Print-to-PDF from a templating layer**: generate a LaTeX/HTML
      facsimile of the form and let the caller print. Diverges from
      CRA's official PDF appearance.

   The XFA XML dumps from CRA's PDFs are saved alongside the PDFs
   in `test/resources/cra/*.xfa.xml` for future reference.

   ## What this namespace currently does

   - `fill-form` / `list-fields` / `validate-field-map`: classic
     AcroForm operations. Used by the synthetic test PDFs; useful
     for any non-XFA template.
   - `t1-2024-field-map` / `bc428-2024-field-map`: stub maps. Will
     need to be reframed as SOM-path maps once the XFA fill path
     is implemented.

   ## Workflow when XFA fill is implemented later

     1. Download the CRA fillable PDF.
     2. Inspect the XFA XML via `(extract-xfa-xml path)` (TODO).
     3. Populate the per-form SOM-path map (TODO).
     4. Call `(fill-xfa template values out)` (TODO).

   See ADR-015 for the architectural placement (renderer ring)."
  (:import [org.apache.pdfbox Loader]
           [org.apache.pdfbox.pdmodel PDDocument]
           [org.apache.pdfbox.pdmodel.interactive.form PDAcroForm PDField]
           [java.io File]))

(defn- ^PDDocument open-pdf [path]
  (cond
    (instance? File path) (Loader/loadPDF ^File path)
    (string? path)        (Loader/loadPDF (File. ^String path))
    :else (throw (ex-info "open-pdf expects a path string or File"
                          {:got (class path)}))))

(defn extract-xfa-xml
  "For an XFA-form PDF (such as CRA's fillable T1/BC428/T2125), return
   the embedded XFA XML stream as a string. Returns nil if the PDF
   has no XFA stream.

   This is the entry point for the (not-yet-implemented) XFA fill path.
   See namespace docstring for context."
  [path]
  (with-open [doc (open-pdf path)]
    (let [form ^PDAcroForm (.getAcroForm (.getDocumentCatalog doc))
          xfa  (when form (.getXFA form))]
      (when xfa
        (String. (.getBytes xfa) "UTF-8")))))

(defn list-fields
  "Return a sorted vector of AcroForm field names present in the PDF
   at `path`. Use this to discover the field names CRA assigned when
   building a new field map.

   **For CRA's XFA-based fillable PDFs this returns only one entry
   (`form1[0]`) — the XFA container.** Use `extract-xfa-xml` to get
   the actual field tree."
  [path]
  (with-open [doc (open-pdf path)]
    (let [form ^PDAcroForm (.getAcroForm (.getDocumentCatalog doc))]
      (if (nil? form)
        []
        (->> (.getFields form)
             (map #(.getFullyQualifiedName ^PDField %))
             sort
             vec)))))

(defn validate-field-map
  "Check that every entry in `field-map` has a non-nil PDF field name.
   Returns {:ok? bool :missing #{kontor-keys-with-nil-fields}}."
  [field-map]
  (let [missing (->> field-map
                     (filter (fn [[_ v]] (nil? v)))
                     (map first)
                     set)]
    {:ok?     (empty? missing)
     :missing missing}))

(defn- format-value
  "Coerce a value to the string form the PDF expects.
   Money → 2-decimal HALF_EVEN string. Other types → str."
  [v]
  (cond
    (nil? v) ""
    (and (map? v) (contains? v :amount) (contains? v :commodity))
    (.toPlainString
     (.setScale ^java.math.BigDecimal (:amount v)
                2 java.math.RoundingMode/HALF_EVEN))
    :else (str v)))

(defn fill-form
  "Fill an AcroForm PDF template.

     template-path : path to the fillable PDF (CRA download).
     values        : map from PDF field-name (String) → value
                     (Money / String / Number).
     out-path      : path to write the filled PDF.
     opts          : {:flatten? bool — flatten so fields are read-only}.

   Returns out-path. Throws if a field name in `values` does not exist
   in the template's AcroForm."
  ([template-path values out-path]
   (fill-form template-path values out-path {}))
  ([template-path values out-path {:keys [flatten?]}]
   (with-open [doc (open-pdf template-path)]
     (let [form ^PDAcroForm (.getAcroForm (.getDocumentCatalog doc))]
       (when (nil? form)
         (throw (ex-info "Template has no AcroForm" {:path template-path})))
       (doseq [[field-name v] values]
         (let [field ^PDField (.getField form (str field-name))]
           (when (nil? field)
             (throw (ex-info "Unknown field in template"
                             {:field field-name :path template-path})))
           (.setValue field (format-value v))))
       (when flatten? (.flatten form))
       (.save doc ^String out-path))
     out-path)))

;; ============================================================================
;; Stub field maps — fill in actual PDF field names after inspecting
;; the corresponding CRA fillable PDF with `list-fields`.
;; ============================================================================

(def t1-2024-field-map
  "T1 General 2024 (5000-R) — AcroForm field-name map.

   Stub: every kontor line we compute is listed here with a `nil`
   placeholder for the actual PDF field name. Replace each `nil` with
   the field name returned by `(list-fields \"path/to/5000-R-24e.pdf\")`."
  {:10100  nil  ; Employment income
   :12000  nil  ; Taxable amount of dividends
   :12100  nil  ; Interest
   :12700  nil  ; Taxable capital gains
   :13500  nil  ; Business income
   :15000  nil  ; Total income
   :20800  nil  ; RRSP deduction
   :21200  nil  ; Union dues
   :22200  nil  ; CPP/QPP on self-employment (deduction)
   :22215  nil  ; CPP enhanced (employment, deduction)
   :23300  nil  ; Total deductions
   :23600  nil  ; Net income
   :26000  nil  ; Taxable income
   :26500  nil  ; Federal tax (brackets)
   :30000  nil  ; Basic personal amount
   :30800  nil  ; CPP base (employment)
   :31000  nil  ; CPP base (self-employment)
   :31200  nil  ; EI premiums
   :31260  nil  ; Canada Employment Amount
   :32300  nil  ; Tuition
   :33099  nil  ; Donations (eligible amount)
   :33500  nil  ; NRTC subtotal
   :33800  nil  ; 15% of 33500
   :34900  nil  ; Donation credit
   :35000  nil  ; Total federal NRTCs
   :40424  nil  ; Federal dividend tax credit
   :42000  nil  ; Net federal tax
   })

(def bc428-2024-field-map
  "BC428 2024 — AcroForm field-name map (stub; fill in real names)."
  {:bc-tax-before-credits nil
   :5804                  nil  ; BC BPA
   :5824                  nil  ; CPP base (emp)
   :5828                  nil  ; CPP base (SE)
   :5832                  nil  ; EI premiums
   :5856                  nil  ; Tuition
   :5896                  nil  ; Donations
   :bc-tax                nil})

(defn render-t1-2024
  "Render a T1 2024 result to a filled PDF.

     t1-result    : output of `kontor.l10n-ca.y2024.t1/compute`.
     template     : path to CRA's 5000-R-24e.pdf.
     out-path     : where to save the filled PDF.

   Will fail with a clear error if the field map still contains nil
   entries (use `list-fields` to inspect the template first)."
  [t1-result template out-path]
  (let [{:keys [ok? missing]} (validate-field-map t1-2024-field-map)
        _ (when-not ok?
            (throw (ex-info "T1 field map has nil entries — fill them in first"
                            {:missing missing
                             :hint "Run `(list-fields \"path/to/5000-R-24e.pdf\")` to discover real field names."})))
        ;; Project t1 line keys → PDF field names → values
        values (into {}
                     (keep (fn [[line-kw field-name]]
                             (when-let [v (get-in t1-result [:t1/lines line-kw])]
                               [field-name v])))
                     t1-2024-field-map)]
    (fill-form template values out-path)))

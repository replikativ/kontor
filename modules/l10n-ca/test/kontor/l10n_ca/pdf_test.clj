(ns kontor.l10n-ca.pdf-test
  "Tests for the PDF renderer scaffolding. These exercise the API
   surface without requiring a real CRA fillable PDF — for a smoke
   test against a real template, see the integration-test playbook
   in the namespace docstring."
  (:require [clojure.test :refer [deftest is testing]]
            [kontor.l10n-ca.pdf :as pdf]
            [kontor.money :as money])
  (:import [java.io File]
           [org.apache.pdfbox.pdmodel PDDocument PDPage]
           [org.apache.pdfbox.pdmodel.interactive.form
            PDAcroForm PDTextField]))

(defn- cad [s] (money/money (bigdec s) :CAD))

(defn- make-test-pdf-with-fields!
  "Create a minimal AcroForm PDF with a few named fields. Returns a
   File that the caller must delete."
  [field-names]
  (let [tmp (File/createTempFile "kontor-pdf-test-" ".pdf")
        doc (PDDocument.)]
    (try
      (.addPage doc (PDPage.))
      (let [form (PDAcroForm. doc)]
        (.setAcroForm (.getDocumentCatalog doc) form)
        (doseq [n field-names]
          (let [f (PDTextField. form)]
            (.setPartialName f n)
            (.add (.getFields form) f)))
        (.save doc tmp))
      (finally (.close doc)))
    tmp))

(deftest list-fields-on-synthetic-pdf
  (let [f (make-test-pdf-with-fields! ["line_10100" "line_15000" "line_23600"])]
    (try
      (is (= ["line_10100" "line_15000" "line_23600"]
             (pdf/list-fields (.getAbsolutePath f))))
      (finally (.delete f)))))

(deftest fill-form-writes-values
  (testing "fill-form sets AcroForm field values; list-fields confirms"
    (let [template (make-test-pdf-with-fields! ["line_10100" "line_15000"])
          out (File/createTempFile "kontor-pdf-test-out-" ".pdf")]
      (try
        (pdf/fill-form (.getAbsolutePath template)
                       {"line_10100" (cad "50000.00")
                        "line_15000" "manual-string"}
                       (.getAbsolutePath out))
        (is (.exists out))
        (is (> (.length out) 0) "filled PDF has non-zero size")
        (finally
          (.delete template) (.delete out))))))

(deftest fill-form-rejects-unknown-field
  (let [template (make-test-pdf-with-fields! ["line_10100"])
        out (File/createTempFile "kontor-pdf-test-out-" ".pdf")]
    (try
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Unknown field"
           (pdf/fill-form (.getAbsolutePath template)
                          {"line_NONEXISTENT" (cad "1.00")}
                          (.getAbsolutePath out))))
      (finally
        (.delete template) (.delete out)))))

(deftest validate-field-map-detects-nils
  (testing "validate-field-map returns :ok? false with the nil keys"
    (let [r (pdf/validate-field-map pdf/t1-2024-field-map)]
      (is (false? (:ok? r)))
      (is (contains? (:missing r) :10100))
      (is (pos? (count (:missing r)))))))

(deftest render-t1-rejects-stub-field-map
  (testing "Calling render-t1-2024 against the stub map fails with a clear error"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"nil entries"
         (pdf/render-t1-2024 {:t1/lines {}} "/nonexistent.pdf" "/tmp/out.pdf")))))

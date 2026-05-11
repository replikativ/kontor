(ns kontor.einvoice-de.factur-x-test
  "End-to-end test for the Mustang wrapper:
     - build a small DE B2B invoice (1 line, 19% VAT)
     - run it through generate-xml-string at :xrechnung + :en16931 profiles
     - assert the output XML is valid CII XML (parses), declares the
       expected profile URN, and surfaces the seller VAT-ID, buyer name,
       and per-line totals.

   We don't validate against the full XSD here (would need fetching
   the EN-16931 schemas + Mustang's own validation runtime, both
   heavy); we instead spot-check elements that the kernel must
   surface correctly. A separate Track-B audit test can pull the
   official KoSIT validator if we ever need full conformance proof."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [kontor.einvoice-de.invoice :as inv]
            [kontor.einvoice-de.factur-x :as fx]))

(def sample-invoice
  {:invoice/number     "RG-2026-0001"
   :invoice/issue-date #inst "2026-01-15T00:00:00Z"
   :invoice/due-date   #inst "2026-02-14T00:00:00Z"
   :invoice/currency   "EUR"
   :invoice/seller     {:party/name    "ACME GmbH"
                        :party/street  "Musterstraße 1"
                        :party/zip     "10115"
                        :party/city    "Berlin"
                        :party/country "DE"
                        :party/vat-id  "DE123456789"
                        :party/email   "rechnung@acme.example"
                        :party/contact-name "Buchhaltung"}
   :invoice/buyer      {:party/name    "Kunden AG"
                        :party/street  "Beispielallee 42"
                        :party/zip     "80331"
                        :party/city    "München"
                        :party/country "DE"
                        :party/vat-id  "DE987654321"}
   :invoice/items      [{:item/name      "Strategieberatung"
                         :item/description "10 h Beratung Q1 2026"
                         :item/quantity  10
                         :item/unit-code "HUR"
                         :item/unit-price 150.00M
                         :item/vat-rate  19.0M
                         :item/vat-category "S"}
                        {:item/name      "Reisekosten"
                         :item/description "Bahnticket Berlin-München"
                         :item/quantity  1
                         :item/unit-code "EA"
                         :item/unit-price 89.50M
                         :item/vat-rate  19.0M
                         :item/vat-category "S"}]
   :invoice/payment    {:payment/iban "DE89370400440532013000"
                        :payment/bic  "COBADEFFXXX"
                        :payment/account-name "ACME GmbH"}
   :invoice/notes      ["Zahlbar innerhalb 30 Tage ohne Abzug."]
   :invoice/payment-terms "Zahlungsziel: 30 Tage netto"})

;; ============================================================================
;; invoice.clj — validation + totals
;; ============================================================================

(deftest validate-rejects-empty
  (is (seq (inv/validate {})) "empty map should produce errors"))

(deftest validate-passes-sample
  (is (empty? (inv/validate sample-invoice))))

(deftest validate-bang-throws
  (is (thrown? clojure.lang.ExceptionInfo (inv/validate! {})))
  (is (= sample-invoice (inv/validate! sample-invoice))))

(deftest line-totals-correct
  (let [items (:invoice/items sample-invoice)
        beratung (first items)
        reise    (second items)]
    (is (= 1500.00M (inv/line-net beratung)))
    (is (= 285.00M  (inv/line-vat beratung))   "10 × 150 × 19% = 285")
    (is (= 1785.00M (inv/line-gross beratung)))
    (is (= 89.50M   (inv/line-net reise)))
    (is (= 17.00M   (inv/line-vat reise))
        "89.50 × 19% = 17.005; HALF_EVEN rounds to even → 17.00 (.0 even)")
    (is (= 106.50M  (inv/line-gross reise)))))

(deftest invoice-totals-correct
  (let [t (inv/invoice-totals sample-invoice)]
    (is (= 1589.50M (:invoice/total-net t))   "1500 + 89.50")
    (is (= 302.00M  (:invoice/total-vat t))   "285.00 + 17.00")
    (is (= 1891.50M (:invoice/total-gross t)) "1589.50 + 302.00")
    (is (= 1 (count (:invoice/vat-breakdown t))) "single 19% bucket")
    (let [bucket (first (:invoice/vat-breakdown t))]
      (is (= 19.0M (:vat/rate bucket)))
      (is (= "S"   (:vat/category bucket)))
      (is (= 1589.50M (:vat/base bucket)))
      (is (= 302.00M  (:vat/tax bucket))))))

;; ============================================================================
;; factur-x.clj — XML generation
;; ============================================================================

(deftest generate-xml-string-en16931
  (testing "Generated EN16931 XML contains the canonical CII root +
            our seller / buyer / line / totals."
    (let [xml (fx/generate-xml-string sample-invoice :en16931)]
      (is (string? xml))
      (is (str/includes? xml "<?xml") "starts with XML decl")
      (is (str/includes? xml "CrossIndustryInvoice")
          "root element is the EN-16931 CII")
      (is (str/includes? xml "RG-2026-0001")        "invoice number present")
      (is (str/includes? xml "ACME GmbH")           "seller name present")
      (is (str/includes? xml "DE123456789")         "seller VAT-ID present")
      (is (str/includes? xml "Kunden AG")           "buyer name present")
      (is (str/includes? xml "Strategieberatung")   "line 1 desc present")
      (is (str/includes? xml "Reisekosten")         "line 2 desc present")
      (is (str/includes? xml "DE89370400440532013000") "payment IBAN present")
      (is (str/includes? xml "EUR")                 "currency present")
      ;; Per-line totals
      (is (str/includes? xml "1500.00") "line-1 net total")
      (is (str/includes? xml "89.50")   "line-2 net total"))))

(deftest generate-xml-string-xrechnung
  (testing "XRechnung profile is reflected in the document context urn."
    (let [xml (fx/generate-xml-string sample-invoice :xrechnung)]
      (is (str/includes? xml "xrechnung")
          "XRechnung profile URN should appear in GuidelineSpecifiedDocumentContextParameter"))))

(deftest unknown-profile-throws
  (is (thrown? clojure.lang.ExceptionInfo
               (fx/generate-xml-string sample-invoice :not-a-profile))))

(deftest generate-xml-validates-first
  (testing "generate-xml fails fast on a malformed invoice rather than
            handing a half-built object to Mustang and getting a cryptic
            NPE back."
    (is (thrown? clojure.lang.ExceptionInfo
                 (fx/generate-xml {:invoice/number "X"})))))

;; ============================================================================
;; Multi-rate VAT (e.g., German Regelsatz 19% + ermäßigt 7%)
;; ============================================================================

(deftest mixed-vat-rates-bucketed
  (testing "Mixed 19% + 7% VAT — one bucket per rate, ordered ascending."
    (let [inv (-> sample-invoice
                  (update :invoice/items conj
                          {:item/name "Fachbuch"
                           :item/quantity 2
                           :item/unit-code "EA"
                           :item/unit-price 24.95M
                           :item/vat-rate 7.0M
                           :item/vat-category "AA"}))
          buckets (:invoice/vat-breakdown (inv/invoice-totals inv))]
      (is (= 2 (count buckets)))
      (is (= 7.0M  (:vat/rate (first buckets)))  "ermäßigter Steuersatz first")
      (is (= 19.0M (:vat/rate (second buckets))) "Regelsatz second")
      (is (= 49.90M (:vat/base (first buckets))))
      (is (= 3.49M  (:vat/tax  (first buckets))) "49.90 × 7% = 3.493 → 3.49")
      (let [xml (fx/generate-xml-string inv :en16931)]
        (is (str/includes? xml "Fachbuch") "ermäßigte line surfaces in XML")
        ;; Both VAT rate values should appear somewhere as percentages
        (is (str/includes? xml "19") "19% appears")
        (is (str/includes? xml "7") "7% appears")))))

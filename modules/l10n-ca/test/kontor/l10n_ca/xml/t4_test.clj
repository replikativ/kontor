(ns kontor.l10n-ca.xml.t4-test
  "Tests for T4 XML generator.

   Round-trip + structural tests against synthetic fixtures, plus
   real XSD-validation tests that point at the CRA-published 2026V4
   schema bundle in `test/resources/cra/info-returns-xsd-2026/`."
  (:require [clojure.data.xml :as xml]
            [clojure.test :refer [deftest is testing]]
            [kontor.l10n-ca.xml.t4 :as t4]
            [kontor.l10n-ca.xml.validation :as v]
            [kontor.money :as money])
  (:import [java.io File]))

(defn- cad [s] (money/money (bigdec s) :CAD))

(def ^:private xsd-path
  "Absolute path to the T4 schema entry-point. We resolve via
   io/file rather than io/resource because the schema bundle is large
   and not put on the classpath."
  (str (System/getProperty "user.dir")
       "/modules/l10n-ca/test/resources/cra/info-returns-xsd-2026"
       "/SchemasEFV-Published - 202601/T619_T4.xsd"))

(defn- xsd-present? []
  (.exists (File. ^String xsd-path)))

(def sample-transmitter
  {:transmitter/account-number "123456789RP0001"
   :transmitter/name           "Acme Bookkeeping Inc."
   :transmitter/country-code   "CAN"
   :transmitter/contact        {:name  "J. Doe"
                                :phone "604-555-0100"
                                :email "j.doe@example.ca"}
   :submission/reference-id    "ACME0001"   ; ≤ 8 chars per char8Type
   :submission/summary-count   1
   :submission/language        :english})

(def sample-summary
  {:t4-summary/employer-bn      "987654321RP0001"
   :t4-summary/employer-name    "Acme Bookkeeping Inc."
   :t4-summary/employer-address {:line-1 "100 Burrard St."
                                 :city "Vancouver"
                                 :province "BC"
                                 :country "CAN"
                                 :postal-code "V6C2H5"}
   :t4-summary/contact          {:name "J. Doe"
                                 :phone "604-555-0100"}
   :t4-summary/tax-year         2024
   :t4-summary/report-type      :original})

(def sample-slip
  {:t4/employer-bn "987654321RP0001"
   :t4/sin "123456789"
   :t4/employee {:surname "Smith" :given "John" :initial "A"}
   :t4/employee-address {:line-1 "1 Pine St"
                         :city "Vancouver"
                         :province "BC"
                         :country "CAN"
                         :postal-code "V6B1A1"}
   :t4/province-of-employment "BC"
   :t4/cpp-qpp-exempt? false
   :t4/ei-exempt?      false
   :t4/report-type :original
   :t4/boxes {:box-14 (cad "50000.00")
              :box-16 (cad "1576.75")
              :box-18 (cad "498.00")
              :box-22 (cad "8000.00")
              :box-24 (cad "50000.00")
              :box-26 (cad "50000.00")}})

;; ============================================================================
;; Structural shape
;; ============================================================================

(deftest submission-structure
  (let [sub (t4/submission {:t619 sample-transmitter
                            :t4-summary sample-summary
                            :slips [sample-slip]})
        xml-str (t4/emit-string sub)]
    (testing "Root element is <Submission>"
      (is (= :Submission (:tag sub))))
    (testing "Wraps T619 + <Return> per ReturnType in T619_T4.xsd"
      (let [tags (mapv :tag (:content sub))]
        (is (= [:T619 :Return] tags))))
    (testing "<Return> contains <T4> which contains slips then summary"
      (let [t4 (-> sub :content (nth 1) :content first)
            t4-content (:content t4)]
        (is (= :T4 (:tag t4)))
        (is (= [:T4Slip :T4Summary] (mapv :tag t4-content)))))
    (testing "Critical content appears in serialized XML"
      (is (re-find #"<bn15>123456789RP0001</bn15>" xml-str)
          "BN wrapped inside TransmitterAccountNumber/bn15")
      (is (re-find #"<sbmt_ref_id>ACME0001"   xml-str))
      (is (re-find #"<lang_cd>E</lang_cd>"    xml-str))
      (is (re-find #"<empt_incamt>50000.00"   xml-str)
          "T4_AMT uses 'empt_incamt' not 'emp_inc_amt'")
      (is (re-find #"<empe_eip_amt>498.00"    xml-str)
          "EI premiums element is 'empe_eip_amt' not 'ei_prem_amt'")
      (is (re-find #"<itx_ddct_amt>8000.00"   xml-str)
          "Income tax deducted is 'itx_ddct_amt' not 'itx_ddctd_amt'")
      (is (re-find #"<bn>987654321RP0001" xml-str)
          "Employer BN tag is 'bn'")
      (is (re-find #"<empt_prov_cd>BC</empt_prov_cd>" xml-str)
          "Province of employment tag is 'empt_prov_cd'"))))

(deftest summary-totals-roll-up
  (testing "Two slips → T4Summary T4_TAMT sums correctly"
    (let [slip-1 (assoc-in sample-slip [:t4/boxes :box-14] (cad "30000.00"))
          slip-2 (assoc-in sample-slip [:t4/boxes :box-14] (cad "70000.00"))
          sub (t4/submission {:t619 sample-transmitter
                              :t4-summary sample-summary
                              :slips [slip-1 slip-2]})
          xml-str (t4/emit-string sub)]
      (is (re-find #"<slp_cnt>2</slp_cnt>" xml-str))
      (is (re-find #"<tot_empt_incamt>100000.00</tot_empt_incamt>" xml-str)))))

(deftest report-type-codes
  (testing "report-type :amended → A (slip and summary)"
    (let [amended (t4/submission
                   {:t619 sample-transmitter
                    :t4-summary (assoc sample-summary
                                       :t4-summary/report-type :amended)
                    :slips [(assoc sample-slip :t4/report-type :amended)]})
          xml-str (t4/emit-string amended)]
      ;; Both the slip and summary emit rpt_tcd; both should be A
      (is (= 2 (count (re-seq #"<rpt_tcd>A</rpt_tcd>" xml-str)))))))

(deftest amounts-formatted-to-cents
  (testing "Money amounts emit with two decimals (HALF_EVEN)"
    (let [slip (assoc-in sample-slip [:t4/boxes :box-16] (cad "1576.755"))
          sub (t4/submission {:t619 sample-transmitter
                              :t4-summary sample-summary
                              :slips [slip]})
          xml-str (t4/emit-string sub)]
      ;; 1576.755 — HALF_EVEN of x.x5 rounds to even neighbor.
      ;; cent digit is 5 (odd), rounds up to 6. → 1576.76
      (is (re-find #"<cpp_cntrb_amt>1576.76</cpp_cntrb_amt>" xml-str)))))

(deftest reparseable
  (let [sub (t4/submission {:t619 sample-transmitter
                            :t4-summary sample-summary
                            :slips [sample-slip]})
        parsed (xml/parse-str (t4/emit-string sub))]
    (is (= :Submission (:tag parsed)))))

;; ============================================================================
;; XSD validation against the CRA-published 2026V4 schema
;; ============================================================================

(deftest valid-submission-passes-xsd
  (if (xsd-present?)
    (let [sub (t4/submission {:t619 sample-transmitter
                              :t4-summary sample-summary
                              :slips [sample-slip]})
          xml-str (t4/emit-string sub)
          {:keys [valid? errors]} (v/validate xsd-path xml-str)]
      (when-not valid?
        (println "\n=== XSD VALIDATION ERRORS ===")
        (doseq [e errors] (println (format "  %s line %d col %d: %s"
                                           (:severity e) (:line e) (:column e)
                                           (:message e))))
        (println "=== XML BEING VALIDATED ===")
        (println xml-str))
      (is valid? "Sample submission must validate against T619_T4.xsd"))
    (println "Skipping XSD validation — CRA XSD bundle not present at:" xsd-path)))

(deftest invalid-bn-fails-xsd
  (when (xsd-present?)
    (let [bad-slip (assoc sample-slip :t4/employer-bn "NOT-A-BN")
          sub (t4/submission {:t619 sample-transmitter
                              :t4-summary sample-summary
                              :slips [bad-slip]})
          {:keys [valid?]} (v/validate xsd-path (t4/emit-string sub))]
      (is (not valid?) "Bad BN should fail XSD validation"))))

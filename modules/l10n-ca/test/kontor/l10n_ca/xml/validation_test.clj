(ns kontor.l10n-ca.xml.validation-test
  "Self-contained tests for the JAXP validator — uses a synthetic XSD +
   XML so the validator itself is proven before pointing at CRA's
   real schemas."
  (:require [clojure.test :refer [deftest is testing]]
            [kontor.l10n-ca.xml.validation :as v])
  (:import [java.io File]))

(def ^:private toy-xsd
  "Tiny self-contained schema: a <slip> element containing a name
   and an amount; amount is a decimal with 2 fractional digits."
  "<?xml version=\"1.0\" encoding=\"UTF-8\"?>
<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\">
  <xs:element name=\"slip\">
    <xs:complexType>
      <xs:sequence>
        <xs:element name=\"name\" type=\"xs:string\"/>
        <xs:element name=\"amount\">
          <xs:simpleType>
            <xs:restriction base=\"xs:decimal\">
              <xs:fractionDigits value=\"2\"/>
              <xs:minInclusive value=\"0\"/>
            </xs:restriction>
          </xs:simpleType>
        </xs:element>
      </xs:sequence>
    </xs:complexType>
  </xs:element>
</xs:schema>")

(defn- with-tmp-xsd [thunk]
  (let [f (File/createTempFile "kontor-xsd-test-" ".xsd")]
    (try
      (spit f toy-xsd)
      (thunk (.getAbsolutePath f))
      (finally (.delete f)))))

(deftest valid-xml-passes
  (with-tmp-xsd
    (fn [xsd]
      (let [xml "<?xml version=\"1.0\"?><slip><name>Alice</name><amount>1234.56</amount></slip>"
            {:keys [valid? errors]} (v/validate xsd xml)]
        (is valid?)
        (is (empty? errors))))))

(deftest invalid-xml-fails-with-errors
  (testing "Negative amount violates <xs:minInclusive value=\"0\"/>"
    (with-tmp-xsd
      (fn [xsd]
        (let [xml "<?xml version=\"1.0\"?><slip><name>Alice</name><amount>-10.00</amount></slip>"
              {:keys [valid? errors]} (v/validate xsd xml)]
          (is (not valid?))
          (is (seq errors))
          (is (some #(or (#{:error :fatal} (:severity %))) errors)))))))

(deftest missing-required-element-fails
  (with-tmp-xsd
    (fn [xsd]
      (let [xml "<?xml version=\"1.0\"?><slip><amount>10.00</amount></slip>"  ; no <name>
            {:keys [valid?]} (v/validate xsd xml)]
        (is (not valid?))))))

(deftest validate-bang-throws-on-failure
  (with-tmp-xsd
    (fn [xsd]
      (let [bad "<?xml version=\"1.0\"?><slip><name>X</name><amount>-1.00</amount></slip>"]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"XSD validation"
             (v/validate! bad xsd)))))))

(deftest validate-bang-passes-through-on-success
  (with-tmp-xsd
    (fn [xsd]
      (let [good "<?xml version=\"1.0\"?><slip><name>X</name><amount>1.00</amount></slip>"]
        (is (= good (v/validate! good xsd)))))))

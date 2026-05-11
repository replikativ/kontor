(ns kontor.l10n-br.cst-test
  (:require [clojure.test :refer [deftest is testing]]
            [kontor.l10n-br.cst :as cst]))

(deftest icms-orig-coverage
  (testing "All 9 origin codes 0-8 present"
    (is (= 9 (count cst/icms-orig)))
    (is (every? #(contains? cst/icms-orig (str %)) (range 9))))
  (testing "Imported flag (Res. SF 13/2012 trigger)"
    (is (false? (cst/imported-origin? "0")))
    (is (true?  (cst/imported-origin? "1")))
    (is (true?  (cst/imported-origin? "2")))
    (is (true?  (cst/imported-origin? "3")))
    (is (false? (cst/imported-origin? "4")))
    (is (false? (cst/imported-origin? "5")))
    (is (true?  (cst/imported-origin? "6")))
    (is (true?  (cst/imported-origin? "7")))
    (is (true?  (cst/imported-origin? "8")))))

(deftest icms-cst-key-codes
  (testing "Regime Normal CSTs present"
    (is (= :ICMS00 (cst/cst-xml-group :icms "00"))
        "00 = Tributada integralmente → <ICMS00>")
    (is (= :ICMS10 (cst/cst-xml-group :icms "10")))
    (is (= :ICMS40 (cst/cst-xml-group :icms "40"))
        "Exempt → <ICMS40>")
    (is (= :ICMS60 (cst/cst-xml-group :icms "60")))
    (is (= :ICMS90 (cst/cst-xml-group :icms "90")))))

(deftest icms-csosn-coverage
  (testing "Simples Nacional CSOSN codes (10 codes)"
    (is (= 10 (count cst/icms-csosn)))
    (is (cst/valid-cst? :icms-csosn "101"))
    (is (cst/valid-cst? :icms-csosn "500"))
    (is (cst/valid-cst? :icms-csosn "900"))
    (is (not (cst/valid-cst? :icms-csosn "099"))
        "Random non-Simples code")))

(deftest ipi-cst-direction
  (testing "Entrada / saída discrimination"
    (is (= :in  (:direction (cst/cst-meta :ipi "00"))))
    (is (= :in  (:direction (cst/cst-meta :ipi "49"))))
    (is (= :out (:direction (cst/cst-meta :ipi "50"))))
    (is (= :out (:direction (cst/cst-meta :ipi "99")))))
  (testing "Tributada vs Não-tributada XML group"
    (is (= :IPITrib (cst/cst-xml-group :ipi "00")))
    (is (= :IPINT   (cst/cst-xml-group :ipi "02")))
    (is (= :IPITrib (cst/cst-xml-group :ipi "50")))
    (is (= :IPINT   (cst/cst-xml-group :ipi "52")))))

(deftest pis-cst-xml-group
  (testing "PIS CST → XML group keyword"
    (is (= :PISAliq (cst/cst-xml-group :pis "01")))
    (is (= :PISQtde (cst/cst-xml-group :pis "03")))
    (is (= :PISNT   (cst/cst-xml-group :pis "04")))
    (is (= :PISOutr (cst/cst-xml-group :pis "49")))))

(deftest cofins-cst-xml-group
  (testing "COFINS CST → XML group keyword"
    (is (= :COFINSAliq (cst/cst-xml-group :cofins "01")))
    (is (= :COFINSQtde (cst/cst-xml-group :cofins "03")))
    (is (= :COFINSNT   (cst/cst-xml-group :cofins "04")))
    (is (= :COFINSOutr (cst/cst-xml-group :cofins "99")))))

(deftest pis-cofins-code-alignment
  (testing "PIS and COFINS share the same code namespace"
    (is (= (set (keys cst/pis-cst))
           (set (keys cst/cofins-cst))))))

(deftest valid-cst-rejects-unknown
  (is (not (cst/valid-cst? :icms "99")))
  (is (not (cst/valid-cst? :ipi "33")))
  (is (not (cst/valid-cst? :pis "AB")))
  (is (not (cst/valid-cst? :unknown-kind "00"))))

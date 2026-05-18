(ns kontor.payroll-br.esocial-test
  "Tests for eSocial XML event builders. We assert the load-bearing
   elements (event-ID format, employer-CNPJ, CPF presence, rubrica
   codes, valor amounts, ide-evento structure) round-trip through
   the data.xml emit. Full XSD validation against the gov.br schemas
   is a P2 followup (we'd need to bundle the XSDs; the consumer can
   re-validate using the engine's XSD pipeline)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.data.xml :as xml]
            [kontor.payroll-br.esocial :as esocial]))

(def employer-cnpj "11.222.333/0001-81")
(def employee-cpf "111.444.777-35")

;; ============================================================================
;; S-1000 — employer master
;; ============================================================================

(deftest s-1000-emits-employer-master
  (let [evt (esocial/build-s-1000-event
             {:employer-cnpj employer-cnpj
              :nm-razao "Acme Ltda"
              :cl-trib "01"
              :timestamp #inst "2026-05-15"})
        xml-str (esocial/emit-xml evt)]
    (testing "Contains the evtInfoEmpregador root"
      (is (str/includes? xml-str "evtInfoEmpregador")))
    (testing "Carries the employer's CNPJ-raiz (8 digits)"
      (is (str/includes? xml-str "11222333")))
    (testing "Carries the razão social"
      (is (str/includes? xml-str "Acme Ltda")))
    (testing "Carries the classificação tributária"
      (is (str/includes? xml-str "<a:classTrib>01</a:classTrib>")))
    (testing "Event ID starts with 'ID1'"
      (is (re-find #"Id=\"ID1[0-9]+\"" xml-str)))))

(deftest s-1000-rejects-invalid-cnpj
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"Invalid CNPJ"
                        (esocial/build-s-1000-event
                         {:employer-cnpj "00000000000000"  ; mathematically blocked
                          :nm-razao "Bad Co"
                          :cl-trib "01"}))))

(deftest s-1000-requires-key-fields
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #":nm-razao required"
                        (esocial/build-s-1000-event
                         {:employer-cnpj employer-cnpj
                          :cl-trib "01"}))))

;; ============================================================================
;; S-1005 — Tabela de Estabelecimentos
;; ============================================================================

(deftest s-1005-emits-estabelecimento
  (let [evt (esocial/build-s-1005-event
             {:employer-cnpj employer-cnpj
              :estab-cnpj employer-cnpj
              :cnae-prep "6201500"
              :ali-rat 2
              :fap 1.0M
              :ini-valid #inst "2026-01-01"})
        xml-str (esocial/emit-xml evt)]
    (testing "Contains the evtTabEstab root"
      (is (str/includes? xml-str "evtTabEstab")))
    (testing "Carries CNAE"
      (is (str/includes? xml-str "6201500")))
    (testing "Carries RAT alíquota"
      (is (str/includes? xml-str "<a:aliqRat>2</a:aliqRat>")))))

;; ============================================================================
;; S-1010 — Tabela de Rubricas
;; ============================================================================

(deftest s-1010-emits-rubrica
  (let [evt (esocial/build-s-1010-event
             {:employer-cnpj employer-cnpj
              :rubrica-code "R001"
              :rubrica-desc "Salário base"
              :nat-rubr "1000"
              :tp-rubr 1
              :ini-valid #inst "2026-01-01"})
        xml-str (esocial/emit-xml evt)]
    (testing "Contains the evtTabRubrica root"
      (is (str/includes? xml-str "evtTabRubrica")))
    (testing "Carries rubrica code"
      (is (str/includes? xml-str "<a:codRubr>R001</a:codRubr>")))
    (testing "Carries rubrica descricao"
      (is (str/includes? xml-str "Salário base")))
    (testing "Carries natureza"
      (is (str/includes? xml-str "<a:natRubr>1000</a:natRubr>")))))

;; ============================================================================
;; S-1020 — Tabela de Lotações
;; ============================================================================

(deftest s-1020-emits-lotacao
  (let [evt (esocial/build-s-1020-event
             {:employer-cnpj employer-cnpj
              :cod-lotacao "LOT01"
              :ini-valid #inst "2026-01-01"})
        xml-str (esocial/emit-xml evt)]
    (testing "Contains the evtTabLotacao root"
      (is (str/includes? xml-str "evtTabLotacao")))
    (testing "Carries the cod-lotacao"
      (is (str/includes? xml-str "<a:codLotacao>LOT01</a:codLotacao>")))
    (testing "Default tpLotacao is 01"
      (is (str/includes? xml-str "<a:tpLotacao>01</a:tpLotacao>")))))

;; ============================================================================
;; S-2200 — Admissão (hire)
;; ============================================================================

(deftest s-2200-emits-admissao
  (let [evt (esocial/build-s-2200-event
             {:employer-cnpj employer-cnpj
              :cpf employee-cpf
              :nis "12345678901"
              :nm-trab "Jane Silva"
              :dt-nascto #inst "1990-01-15"
              :dt-admissao #inst "2026-01-15"
              :matricula "EMP-001"
              :remuneracao 5000M})
        xml-str (esocial/emit-xml evt)]
    (testing "Contains the evtAdmissao root"
      (is (str/includes? xml-str "evtAdmissao")))
    (testing "Carries the employee CPF (digits only)"
      (is (str/includes? xml-str "11144477735")))
    (testing "Carries the employee name"
      (is (str/includes? xml-str "Jane Silva")))
    (testing "Carries the salary"
      (is (str/includes? xml-str "5000.00")))))

(deftest s-2200-rejects-invalid-cpf
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"Invalid CPF"
                        (esocial/build-s-2200-event
                         {:employer-cnpj employer-cnpj
                          :cpf "00000000000"   ; blacklisted all-same
                          :nis "12345678901"
                          :nm-trab "Bad"
                          :dt-nascto #inst "1990-01-15"
                          :dt-admissao #inst "2026-01-15"
                          :matricula "E1"}))))

;; ============================================================================
;; S-2299 — Desligamento (termination)
;; ============================================================================

(deftest s-2299-emits-desligamento
  (let [evt (esocial/build-s-2299-event
             {:employer-cnpj employer-cnpj
              :cpf employee-cpf
              :matricula "EMP-001"
              :dt-deslig #inst "2026-06-30"
              :mtv-deslig :dismissal-without-cause})
        xml-str (esocial/emit-xml evt)]
    (testing "Contains the evtDeslig root"
      (is (str/includes? xml-str "evtDeslig")))
    (testing "Carries the cause code 02 (dismissal without cause)"
      (is (str/includes? xml-str "<a:mtvDeslig>02</a:mtvDeslig>")))
    (testing "Carries the termination date"
      (is (str/includes? xml-str "2026-06-30")))))

(deftest s-2299-cause-keyword-mapping
  (testing "All canonical kontor termination keywords map"
    (doseq [[kw expected-code] esocial/termination-cause-codes]
      (let [evt (esocial/build-s-2299-event
                 {:employer-cnpj employer-cnpj
                  :cpf employee-cpf
                  :matricula "EMP-001"
                  :dt-deslig #inst "2026-06-30"
                  :mtv-deslig kw})
            xml-str (esocial/emit-xml evt)]
        (is (str/includes? xml-str (str "<a:mtvDeslig>"
                                        expected-code
                                        "</a:mtvDeslig>")))))))

(deftest s-2299-rejects-unknown-cause-keyword
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"Unknown termination cause"
                        (esocial/build-s-2299-event
                         {:employer-cnpj employer-cnpj
                          :cpf employee-cpf
                          :matricula "EMP-001"
                          :dt-deslig #inst "2026-06-30"
                          :mtv-deslig :totally-fictional}))))

;; ============================================================================
;; S-2300 / S-2399 — Trabalhador sem vínculo
;; ============================================================================

(deftest s-2300-emits-tsv-inicio
  (let [evt (esocial/build-s-2300-event
             {:employer-cnpj employer-cnpj
              :cpf employee-cpf
              :nm-trab "Director Person"
              :dt-nascto #inst "1970-01-01"
              :dt-inicio #inst "2026-01-15"
              :cod-categ 721  ; diretor não-empregado
              })
        xml-str (esocial/emit-xml evt)]
    (testing "Contains the evtTSVInicio root"
      (is (str/includes? xml-str "evtTSVInicio")))
    (testing "Carries cod-categ 721"
      (is (str/includes? xml-str "<a:codCateg>721</a:codCateg>")))))

(deftest s-2399-emits-tsv-termino
  (let [evt (esocial/build-s-2399-event
             {:employer-cnpj employer-cnpj
              :cpf employee-cpf
              :dt-termino #inst "2026-06-30"
              :mtv-deslig :resignation})
        xml-str (esocial/emit-xml evt)]
    (testing "Contains the evtTSVTermino root"
      (is (str/includes? xml-str "evtTSVTermino")))
    (testing "Carries cause code 07 (resignation)"
      (is (str/includes? xml-str "<a:mtvDesligTSV>07</a:mtvDesligTSV>")))))

;; ============================================================================
;; S-1200 — Remuneração (per-employee monthly)
;; ============================================================================

(deftest s-1200-emits-remuneracao-with-rubricas
  (let [fact {:employment :emp/jane
              :gross 5000M
              :net 4352.50M
              :components [{:kind :base-wage :amount 5000M
                            :employer-side? false :rubrica "R001"}
                           {:kind :inss-employee :amount -400M
                            :employer-side? false :rubrica "R200"}
                           {:kind :irrf-employee :amount -247.50M
                            :employer-side? false :rubrica "R210"}]
              :jurisdiction-specific-codes {}}
        evt (esocial/build-s-1200-event
             {:employer-cnpj employer-cnpj
              :cpf employee-cpf
              :matricula "EMP-001"
              :per-apur #inst "2026-05-01"
              :fact fact
              :cod-lotacao "LOT01"})
        xml-str (esocial/emit-xml evt)]
    (testing "Contains the evtRemun root"
      (is (str/includes? xml-str "evtRemun")))
    (testing "Carries every rubrica code from the fact"
      (is (str/includes? xml-str "<a:codRubr>R001</a:codRubr>"))
      (is (str/includes? xml-str "<a:codRubr>R200</a:codRubr>"))
      (is (str/includes? xml-str "<a:codRubr>R210</a:codRubr>")))
    (testing "Carries the gross amount (HALF-EVEN to 2dp)"
      (is (str/includes? xml-str "<a:vrRubr>5000.00</a:vrRubr>")))
    (testing "Deduction amounts come through as positive (sign-stripped per S-1200 convention)"
      (is (str/includes? xml-str "<a:vrRubr>400.00</a:vrRubr>"))
      (is (str/includes? xml-str "<a:vrRubr>247.50</a:vrRubr>")))
    (testing "Carries the period-apuração as 2026-05"
      (is (str/includes? xml-str "<a:perApur>2026-05</a:perApur>")))))

;; ============================================================================
;; S-1210 — Pagamentos
;; ============================================================================

(deftest s-1210-emits-pagamentos
  (let [evt (esocial/build-s-1210-event
             {:employer-cnpj employer-cnpj
              :cpf employee-cpf
              :per-apur #inst "2026-05-01"
              :dt-pgto #inst "2026-06-05"
              :net-amount 4352.50M
              :ide-dm-dev "DM-2026-05-1"})
        xml-str (esocial/emit-xml evt)]
    (testing "Contains the evtPgtos root"
      (is (str/includes? xml-str "evtPgtos")))
    (testing "Carries the net payment amount"
      (is (str/includes? xml-str "<a:vrLiq>4352.50</a:vrLiq>")))
    (testing "Carries the payment date"
      (is (str/includes? xml-str "2026-06-05")))))

;; ============================================================================
;; S-1299 — Fechamento dos Eventos Periódicos
;; ============================================================================

(deftest s-1299-emits-fechamento
  (let [evt (esocial/build-s-1299-event
             {:employer-cnpj employer-cnpj
              :per-apur #inst "2026-05-01"})
        xml-str (esocial/emit-xml evt)]
    (testing "Contains the evtFechaEvPer root"
      (is (str/includes? xml-str "evtFechaEvPer")))
    (testing "Defaults: evtRemun=S, evtAqProd=N"
      (is (str/includes? xml-str "<a:evtRemun>S</a:evtRemun>"))
      (is (str/includes? xml-str "<a:evtAqProd>N</a:evtAqProd>")))
    (testing "Carries the period"
      (is (str/includes? xml-str "<a:perApur>2026-05</a:perApur>")))))

;; ============================================================================
;; XML well-formedness round-trip
;; ============================================================================

(deftest emitted-xml-is-well-formed
  "Verify the emitted XML re-parses cleanly (a P2 substitute for
   full XSD validation; if data.xml produces a string we can re-parse,
   the structure is at least well-formed)."
  (let [evts [(esocial/build-s-1000-event
               {:employer-cnpj employer-cnpj
                :nm-razao "Acme"
                :cl-trib "01"})
              (esocial/build-s-1010-event
               {:employer-cnpj employer-cnpj
                :rubrica-code "R001"
                :rubrica-desc "Salario"
                :nat-rubr "1000"
                :tp-rubr 1
                :ini-valid #inst "2026-01-01"})
              (esocial/build-s-1299-event
               {:employer-cnpj employer-cnpj
                :per-apur #inst "2026-05-01"})]]
    (doseq [evt evts]
      (let [xml-str (esocial/emit-xml evt)
            reparsed (xml/parse-str xml-str)]
        (is (some? reparsed))
        (is (= :xmlns.http%3A%2F%2Fwww.esocial.gov.br%2Fschema%2Fevt/eSocial
               (:tag reparsed)))))))

;; ============================================================================
;; Validation helpers
;; ============================================================================

(deftest validate-fact-for-s1200-passes-clean-fact
  (let [fact {:components [{:kind :base-wage :amount 5000M
                            :rubrica "R001"}
                           {:kind :inss-employee :amount -400M
                            :rubrica "R200"}]}]
    (is (= fact (esocial/validate-fact-for-s1200! fact)))))

(deftest validate-fact-for-s1200-throws-on-missing-rubrica
  (let [fact {:components [{:kind :base-wage :amount 5000M}
                           {:kind :inss-employee :amount -400M
                            :rubrica "R200"}]}]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"missing :rubrica"
                          (esocial/validate-fact-for-s1200! fact)))))

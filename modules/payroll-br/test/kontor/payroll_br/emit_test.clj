(ns kontor.payroll-br.emit-test
  "Tests for the BrESocialEmitProvider + termination/hire tx-data
   builders."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kontor.payroll-br.emit :as emit]
            [kontor.provider.payroll-provider :as pp]))

(def employer-cnpj "11.222.333/0001-81")
(def employee-cpf "111.444.777-35")

(defn- sample-fact []
  {:employment :emp/jane
   :gross 5000M
   :net 4352.50M
   :components [{:kind :base-wage :amount 5000M
                 :employer-side? false :rubrica "R001"}
                {:kind :inss-employee :amount -400M
                 :employer-side? false :rubrica "R200"}
                {:kind :irrf-employee :amount -247.50M
                 :employer-side? false :rubrica "R210"}]
   :jurisdiction-specific-codes {:engine :rh-sistemas
                                 :employee-external-id "11144477735"}})

;; ============================================================================
;; BrESocialEmitProvider
;; ============================================================================

(deftest provider-requires-employer-cnpj
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"employer-cnpj required"
                        (emit/make-provider {:per-apur #inst "2026-05-01"
                                             :cod-lotacao "LOT01"}))))

(deftest provider-requires-per-apur
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"per-apur required"
                        (emit/make-provider {:employer-cnpj employer-cnpj
                                             :cod-lotacao "LOT01"}))))

(deftest provider-emits-three-docs-per-fact
  "Each PayrollFact produces S-1200 + S-1210 (2 docs/fact) + a single
   S-1299 fechamento per pay-period."
  (let [provider (emit/make-provider
                  {:employer-cnpj employer-cnpj
                   :per-apur #inst "2026-05-01"
                   :cod-lotacao "LOT01"})
        facts [(sample-fact)]
        docs (pp/emit-payroll-events provider facts
                                     {:pay-period-eid 1
                                      :entity-eid 2})]
    (testing "Three audit-docs returned (S-1200 + S-1210 + S-1299)"
      (is (= 3 (count docs))))
    (testing "All carry :kontor.audit-doc/category :payroll-filing"
      (is (every? #(= :payroll-filing (:kontor.audit-doc/category %)) docs)))
    (testing "All carry :kontor.audit-doc/language :pt-br"
      (is (every? #(= :pt-br (:kontor.audit-doc/language %)) docs)))
    (testing "All carry :kontor.audit-doc/inline-payload with the eSocial XML"
      (is (every? #(string? (:kontor.audit-doc/inline-payload %)) docs))
      (is (every? #(str/includes? (:kontor.audit-doc/inline-payload %) "eSocial")
                  docs)))))

(deftest provider-emits-per-employee
  (let [provider (emit/make-provider
                  {:employer-cnpj employer-cnpj
                   :per-apur #inst "2026-05-01"
                   :cod-lotacao "LOT01"})
        facts [(sample-fact)
               (assoc (sample-fact)
                      :employment :emp/john
                      :jurisdiction-specific-codes
                      {:engine :rh-sistemas
                       :employee-external-id "12345678909"})]
        docs (pp/emit-payroll-events provider facts
                                     {:pay-period-eid 1 :entity-eid 2})]
    (testing "Two employees → 2 × 2 + 1 fechamento = 5 docs"
      (is (= 5 (count docs))))
    (testing "S-1200 docs reference both CPFs"
      (let [s1200-payloads (->> docs
                                (filter #(str/includes? (:kontor.audit-doc/code %) "S1200"))
                                (mapv :kontor.audit-doc/inline-payload))]
        (is (= 2 (count s1200-payloads)))
        (is (some #(str/includes? % "11144477735") s1200-payloads))
        (is (some #(str/includes? % "12345678909") s1200-payloads))))))

(deftest provider-fechamento-is-singleton
  ;; Exactly one S-1299 (fechamento) regardless of fact count.
  (let [provider (emit/make-provider
                  {:employer-cnpj employer-cnpj
                   :per-apur #inst "2026-05-01"
                   :cod-lotacao "LOT01"})
        facts [(sample-fact)
               (assoc (sample-fact) :employment :emp/two)
               (assoc (sample-fact) :employment :emp/three)]
        docs (pp/emit-payroll-events provider facts
                                     {:pay-period-eid 1 :entity-eid 2})
        s1299-docs (filter #(str/includes? (:kontor.audit-doc/code %) "S1299") docs)]
    (is (= 1 (count s1299-docs)))))

(deftest provider-emit-doc-id-is-stable
  "Audit-doc codes use the (cnpj, cpf, period) tuple so repeated emits
   stay deduplicatable."
  (let [provider (emit/make-provider
                  {:employer-cnpj employer-cnpj
                   :per-apur #inst "2026-05-01"
                   :cod-lotacao "LOT01"})
        facts [(sample-fact)]
        docs-1 (pp/emit-payroll-events provider facts
                                       {:pay-period-eid 1 :entity-eid 2})
        docs-2 (pp/emit-payroll-events provider facts
                                       {:pay-period-eid 1 :entity-eid 2})]
    (testing "Codes match across calls"
      (is (= (set (map :kontor.audit-doc/code docs-1))
             (set (map :kontor.audit-doc/code docs-2)))))))

;; ============================================================================
;; Table events
;; ============================================================================

(deftest table-events-build-audit-docs
  (let [docs (emit/build-table-event-audit-docs
              {:event-specs
               [{:event-type :s-1000
                 :opts {:employer-cnpj employer-cnpj
                        :nm-razao "Acme Ltda"
                        :cl-trib "01"}}
                {:event-type :s-1010
                 :opts {:employer-cnpj employer-cnpj
                        :rubrica-code "R001"
                        :rubrica-desc "Salário base"
                        :nat-rubr "1000"
                        :tp-rubr 1
                        :ini-valid #inst "2026-01-01"}}
                {:event-type :s-1020
                 :opts {:employer-cnpj employer-cnpj
                        :cod-lotacao "LOT01"
                        :ini-valid #inst "2026-01-01"}}]})]
    (testing "Three docs"
      (is (= 3 (count docs))))
    (testing "Each tagged :payroll-filing"
      (is (every? #(= :payroll-filing (:kontor.audit-doc/category %)) docs)))
    (testing "Each carries inline XML payload"
      (is (every? #(str/includes? (:kontor.audit-doc/inline-payload %) "eSocial") docs)))))

(deftest table-events-reject-unknown-type
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"Unknown event-type"
                        (emit/build-table-event-audit-docs
                         {:event-specs
                          [{:event-type :s-9999
                            :opts {:employer-cnpj employer-cnpj}}]}))))

;; ============================================================================
;; terminate-employment-tx-data
;; ============================================================================

(deftest terminate-employment-emits-s-2299-audit-doc
  (let [[audit-doc emp-update]
        (emit/terminate-employment-tx-data
         nil
         {:employment-eid 42
          :employer-cnpj employer-cnpj
          :cpf employee-cpf
          :matricula "EMP-001"
          :last-day-worked #inst "2026-06-30"
          :termination-reason :dismissal-without-cause})]
    (testing "Audit-doc carries :payroll-filing category"
      (is (= :payroll-filing (:kontor.audit-doc/category audit-doc))))
    (testing "Audit-doc carries :pt-br language"
      (is (= :pt-br (:kontor.audit-doc/language audit-doc))))
    (testing "Audit-doc inline-payload contains S-2299 XML"
      (is (str/includes? (:kontor.audit-doc/inline-payload audit-doc)
                         "evtDeslig"))
      (is (str/includes? (:kontor.audit-doc/inline-payload audit-doc)
                         "<a:mtvDeslig>02</a:mtvDeslig>")))
    (testing "Employment update transitions state to :terminated"
      (is (= 42 (:db/id emp-update)))
      (is (= :terminated (:kontor.employment/state emp-update)))
      (is (= #inst "2026-06-30" (:kontor.employment/end-date emp-update)))
      (is (= :dismissal-without-cause (:kontor.employment/termination-reason emp-update))))))

(deftest terminate-employment-requires-keys
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"required"
                        (emit/terminate-employment-tx-data
                         nil {:employment-eid 42}))))

;; ============================================================================
;; hire-employee-tx-data
;; ============================================================================

(deftest hire-employee-emits-s-2200-audit-doc
  (let [[audit-doc] (emit/hire-employee-tx-data
                     {:employer-cnpj employer-cnpj
                      :cpf employee-cpf
                      :nis "12345678901"
                      :nm-trab "Jane Silva"
                      :dt-nascto #inst "1990-01-15"
                      :dt-admissao #inst "2026-01-15"
                      :matricula "EMP-001"
                      :remuneracao 5000M})]
    (testing "Audit-doc carries :payroll-filing category"
      (is (= :payroll-filing (:kontor.audit-doc/category audit-doc))))
    (testing "Audit-doc carries :pt-br language"
      (is (= :pt-br (:kontor.audit-doc/language audit-doc))))
    (testing "Inline payload contains S-2200 XML"
      (is (str/includes? (:kontor.audit-doc/inline-payload audit-doc)
                         "evtAdmissao"))
      (is (str/includes? (:kontor.audit-doc/inline-payload audit-doc)
                         "Jane Silva")))))

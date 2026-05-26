(ns kontor.payroll-mx.adapter-test
  "End-to-end kernel-substrate run-payroll! exercising the MX bridge
   adapter against a CONTPAQi CSV fixture. Mirrors
   `kontor.payroll-ca.e2e-test/bilingual-payroll-end-to-end` as the
   structural template, adapted to the MX shape (single ledger,
   single language).

   Asserts the orchestrator produces:
     - a :payroll-run row with control totals + provider-id pointing
       at the wrapped MX engine (:contpaqi-nominas);
     - a balanced :transaction (Σ posting amount = 0 per ledger ×
       commodity);
     - one CFDI Nómina audit-doc per employment with
       `:kontor.audit-doc/category :payroll-filing` +
       `:kontor.audit-doc/language :es-mx` (note 86 P0-86-2)."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.hr.compensation :as comp]
            [kontor.hr.core :as hr]
            [kontor.hr.employment :as employment]
            [kontor.hr.pay-period :as pp]
            [kontor.hr.payroll :as payroll]
            [kontor.hr.person :as person]
            [kontor.payroll-mx.adapter :as mx-adapter]
            [kontor.payroll-mx.compute :as mx-compute]
            [kontor.payroll-mx.emit :as mx-emit])
  (:import [java.math BigDecimal]))

;; ============================================================================
;; Bootstrap — minimal MX-style chart + the HR substrate
;; ============================================================================

(defn- bootstrap []
  (let [conn (core/create-test-db)]
    (hr/install! conn)
    (d/transact
     conn
     [{:db/id "mxn"
       :kontor.commodity/symbol "MXN"
       :kontor.commodity/name "Peso mexicano"
       :kontor.commodity/precision 2
       :kontor.commodity/iso-4217 "MXN"}
      {:db/id "ent-acme-mx"
       :kontor.entity/code "ACME-MX"
       :kontor.entity/name "Acme México S.A. de C.V."
       :kontor.entity/kind :operating}
      {:db/id "journal-nom"
       :kontor.journal/code "NOM"
       :kontor.journal/name "Nómina"
       :kontor.journal/type :general
       :kontor.journal/active true}
      {:db/id "period-2026-05"
       :kontor.period/name "2026-05"
       :kontor.period/start #inst "2026-05-01"
       :kontor.period/end #inst "2026-06-01"}
      ;; SAT Código Agrupador chart fragments — the same set the
      ;; legacy MX e2e test installs.
      {:db/id "acct-601-01"
       :kontor.account/code "601.01"
       :kontor.account/path "Gastos:Sueldos"
       :kontor.account/name "Sueldos y Salarios"
       :kontor.account/type :expense
       :kontor.account/active true}
      {:db/id "acct-601-02"
       :kontor.account/code "601.02"
       :kontor.account/path "Gastos:Aguinaldo"
       :kontor.account/name "Aguinaldo y Prima Vacacional"
       :kontor.account/type :expense
       :kontor.account/active true}
      {:db/id "acct-601-05"
       :kontor.account/code "601.05"
       :kontor.account/path "Gastos:IMSS-Patron"
       :kontor.account/name "IMSS patrón"
       :kontor.account/type :expense
       :kontor.account/active true}
      {:db/id "acct-601-06"
       :kontor.account/code "601.06"
       :kontor.account/path "Gastos:INFONAVIT-Patron"
       :kontor.account/name "INFONAVIT patrón"
       :kontor.account/type :expense
       :kontor.account/active true}
      {:db/id "acct-601-84"
       :kontor.account/code "601.84"
       :kontor.account/path "Gastos:Prestaciones"
       :kontor.account/name "Otras prestaciones"
       :kontor.account/type :expense
       :kontor.account/active true}
      {:db/id "acct-206-01"
       :kontor.account/code "206.01"
       :kontor.account/path "Pasivos:SueldosPorPagar"
       :kontor.account/name "Sueldos por pagar"
       :kontor.account/type :liability
       :kontor.account/active true}
      {:db/id "acct-206-04"
       :kontor.account/code "206.04"
       :kontor.account/path "Pasivos:ISRPorPagar"
       :kontor.account/name "ISR por pagar"
       :kontor.account/type :liability
       :kontor.account/active true}
      {:db/id "acct-206-05"
       :kontor.account/code "206.05"
       :kontor.account/path "Pasivos:IMSSPorPagar"
       :kontor.account/name "IMSS por pagar"
       :kontor.account/type :liability
       :kontor.account/active true}
      {:db/id "acct-206-06"
       :kontor.account/code "206.06"
       :kontor.account/path "Pasivos:INFONAVITPorPagar"
       :kontor.account/name "INFONAVIT por pagar"
       :kontor.account/type :liability
       :kontor.account/active true}])
    conn))

(defn- account-eid [db code]
  (d/q '[:find ?e . :in $ ?c :where [?e :kontor.account/code ?c]] db code))

(defn- accounts-map [db]
  {"601.01" (account-eid db "601.01")
   "601.02" (account-eid db "601.02")
   "601.05" (account-eid db "601.05")
   "601.06" (account-eid db "601.06")
   "601.84" (account-eid db "601.84")
   "206.01" (account-eid db "206.01")
   "206.04" (account-eid db "206.04")
   "206.05" (account-eid db "206.05")
   "206.06" (account-eid db "206.06")})

(def employer-cfdi
  "CFDI Emisor data — what every CFDI Nómina envelope carries."
  {:rfc "AAA010101AAA"
   :nombre "Acme México S.A. de C.V."
   :registro-patronal "B1234567890"
   :regimen-fiscal "601"})

(defn- employee-cfdi-receptor
  "Build the CFDI Receptor map for an MX employee. The legacy
   CFDI emitter consumes this shape; the bridge layer's
   `:employee-cfdi-data` opt is keyed by :employment eid → this map."
  [{:keys [rfc curp employee-code name nss]}]
  {:rfc rfc
   :curp curp
   :nss (or nss "12345678901")
   :tipo-contrato "01"
   :tipo-regimen "02"
   :tipo-jornada "01"
   :periodicidad-pago :semi-monthly
   :salario-base-cot-apor 500.00M
   :salario-diario 500.00M
   :antiguedad "P5Y"
   :fecha-inicio-rel-laboral #inst "2020-01-15"
   :num-empleado employee-code
   :nombre name
   :domicilio-fiscal "45050"
   :regimen-fiscal-receptor "605"
   :clave-ent-fed "NLE"})

;; ============================================================================
;; Headline test — run-payroll! drives the MX bridge end-to-end
;; ============================================================================

(deftest run-payroll-against-mx-adapter-end-to-end
  (testing "kontor.hr.payroll/run-payroll! drives the MX bridge: parses a
            CONTPAQi CSV → builds canonical PayrollFacts → emits balanced
            postings + CFDI Nómina audit-docs in one atomic transaction."
    (let [conn (bootstrap)
          db   (d/db conn)
          mxn       (d/q '[:find ?e . :where [?e :kontor.commodity/symbol "MXN"]] db)
          entity    (d/q '[:find ?e . :where [?e :kontor.entity/code "ACME-MX"]] db)
          journal   (d/q '[:find ?e . :where [?e :kontor.journal/code "NOM"]] db)
          fiscal-pp (d/q '[:find ?e . :where [?e :kontor.period/name "2026-05"]] db)
          ;; Persons + employments matching the CONTPAQi CSV fixture (E001 + E002).
          _ (person/create-person!
             conn {:external-id "P-E001"
                   :given-name "Juan" :family-name "Pérez"})
          _ (person/create-person!
             conn {:external-id "P-E002"
                   :given-name "Ana" :family-name "Gómez"})
          db (d/db conn)
          juan-p (hr/person-by-external-id db "P-E001")
          ana-p  (hr/person-by-external-id db "P-E002")
          _ (employment/hire! conn {:code "EMP-E001"
                                    :person juan-p :entity entity
                                    :start-date #inst "2020-01-15"
                                    :job-title "Operario"})
          _ (employment/hire! conn {:code "EMP-E002"
                                    :person ana-p :entity entity
                                    :start-date #inst "2020-01-15"
                                    :job-title "Auxiliar"})
          db (d/db conn)
          juan-e (hr/employment-by-code db "EMP-E001")
          ana-e  (hr/employment-by-code db "EMP-E002")
          _ (comp/set-compensation!
             conn {:employment juan-e
                   :effective-from #inst "2020-01-15"
                   :commodity mxn
                   :components [{:kind :base-wage :amount 180000M
                                 :period :annual}]})
          _ (comp/set-compensation!
             conn {:employment ana-e
                   :effective-from #inst "2020-01-15"
                   :commodity mxn
                   :components [{:kind :base-wage :amount 144000M
                                 :period :annual}]})
          _ (pp/create-pay-period!
             conn {:code "ACME-MX-2026-05"
                   :entity entity
                   :start-date #inst "2026-05-01"
                   :end-date #inst "2026-05-15"
                   :frequency :semi-monthly
                   :fiscal-period fiscal-pp})
          pp-eid (hr/pay-period-by-code (d/db conn) "ACME-MX-2026-05")
          db (d/db conn)
          accounts (accounts-map db)
          ;; Resolve the CSV fixture from the classpath (note: it
          ;; lives under modules/payroll-mx/test/resources).
          csv-source
          (io/resource "kontor/payroll_mx/fixtures/contpaqi-sample.csv")
          _ (is (some? csv-source)
                "CONTPAQi fixture must be reachable on the classpath")
          ;; Build the bridge trio. The MxEngineProvider stays the
          ;; CONTPAQi default; the MxCfdiEmitter stays the default.
          providers
          (mx-adapter/make-mx-kontor-providers
           {:mx-engine (mx-compute/make-contpaqi-nominas-provider)
            :mx-emitter (mx-emit/make-cfdi-nomina-provider)
            :commodity mxn
            :employer employer-cfdi
            :employee-cfdi-data
            {juan-e (employee-cfdi-receptor
                     {:rfc "ABCD800101AB1"
                      :curp "ABCD800101HDFRRR01"
                      :employee-code "E001"
                      :name "Juan Pérez García"})
             ana-e (employee-cfdi-receptor
                    {:rfc "EFGH900202EF2"
                     :curp "EFGH900202MDFRRR02"
                     :employee-code "E002"
                     :name "Ana Gómez Hernández"})}
            :no-certificado "30001000000400002434"
            :certificado "MIIF..."
            :lugar-expedicion "45050"})
          ;; The CONTPAQi fixture keys rows by RFC; the run needs a
          ;; map from RFC → :employment eid to project rows onto the
          ;; canonical PayrollFacts.
          rfc->emp {"ABCD800101AB1" juan-e
                    "EFGH900202EF2" ana-e}
          report
          (payroll/run-payroll!
           conn {:pay-period pp-eid
                 :entity entity
                 :employments [juan-e ana-e]
                 :compute-provider (:compute-provider providers)
                 :posting-builder  (:posting-builder providers)
                 :emit-provider    (:emit-provider providers)
                 :accounts accounts
                 :variable-inputs {:csv-source csv-source
                                   :employment-by-rfc-map rfc->emp}
                 :run-code "ACME-MX-2026-05-001"
                 :tx-code "TX-ACME-MX-2026-05"
                 :journal journal
                 :commodity mxn})
          db (:db-after report)
          run-eid (d/q '[:find ?r . :in $ ?c
                         :where [?r :kontor.payroll-run/code ?c]]
                       db "ACME-MX-2026-05-001")
          run (d/pull db
                      '[* {:kontor.payroll-run/payroll-transaction
                           [:kontor.transaction/external-id
                            {:kontor.posting/_transaction
                             [:kontor.posting/amount
                              {:kontor.posting/account [:kontor.account/code]}
                              {:kontor.posting/commodity [:kontor.commodity/symbol]}]}]}
                        {:kontor.payroll-run/emit-docs
                         [:kontor.audit-doc/code
                          :kontor.audit-doc/category
                          :kontor.audit-doc/language
                          :kontor.audit-doc/type]}]
                      run-eid)]
      (testing "Payroll-run row is created with the wrapped engine's provider-id"
        (is (some? run-eid))
        (is (= :computed (:kontor.payroll-run/state run)))
        (is (= :contpaqi-nominas (:kontor.payroll-run/provider-id run))
            "Bridge surfaces the wrapped MxEngineProvider's vendor-id")
        ;; Per CONTPAQi fixture + ADR-075 PayrollFacts sum invariant
        ;; (`gross = Σ positive employee-side component amounts`):
        ;;   Juan: 7500 sueldo + 250 hora-extra-doble + 25 subsidio = 7775
        ;;   Ana:  6000 sueldo = 6000
        ;;   gross-total = 13775 (subsidio-al-empleo is :otro-pago,
        ;;   paid to worker — sums into gross per the substrate's
        ;;   sum-rule; it nets out against ISR in the GL via 206.04)
        (is (= 13775.00M (:kontor.payroll-run/control-total-gross run))))
      (testing "Posting legs sum to zero per ledger × commodity"
        (let [postings (-> run
                           :kontor.payroll-run/payroll-transaction
                           :kontor.posting/_transaction)
              sum (reduce (fn [^BigDecimal a {:kontor.posting/keys [amount]}]
                            (.add a ^BigDecimal amount))
                          0M postings)]
          (is (pos? (count postings))
              "Bridge produced postings")
          (is (zero? (.compareTo ^BigDecimal sum 0M))
              "Posting sum must be zero")))
      (testing "Postings route via SAT Código Agrupador accounts"
        (let [postings (-> run
                           :kontor.payroll-run/payroll-transaction
                           :kontor.posting/_transaction)
              codes-seen (->> postings
                              (map (comp :kontor.account/code :kontor.posting/account))
                              set)]
          ;; Sueldos (worker percep) → 601.01
          (is (contains? codes-seen "601.01"))
          ;; ISR + Subsidio → 206.04
          (is (contains? codes-seen "206.04"))
          ;; IMSS (worker + patron) → 206.05
          (is (contains? codes-seen "206.05"))
          ;; IMSS patrón expense → 601.05
          (is (contains? codes-seen "601.05"))
          ;; Net pay → 206.01
          (is (contains? codes-seen "206.01"))))
      (testing "Emit provider produced one CFDI Nómina audit-doc per employment"
        (let [docs (:kontor.payroll-run/emit-docs run)]
          (is (= 2 (count docs))
              "One :audit-doc per employment in the run")
          (testing "Each audit-doc carries the MX canonical category + language"
            (doseq [doc docs]
              (is (= :payroll-filing (:kontor.audit-doc/category doc))
                  ":kontor.audit-doc/category :payroll-filing per note 86 P0-86-2")
              (is (= :es-mx (:kontor.audit-doc/language doc))
                  ":kontor.audit-doc/language :es-mx per ADR-082")
              (is (= :payroll-cfdi-xml (:kontor.audit-doc/type doc)))))))
      (testing "CFDI audit-doc count matches the substrate's run × employment count"
        (let [filing-docs
              (d/q '[:find [?e ...]
                     :where [?e :kontor.audit-doc/category :payroll-filing]]
                   db)]
          (is (>= (count filing-docs) 2)))))))

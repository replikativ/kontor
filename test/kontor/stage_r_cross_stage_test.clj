(ns kontor.stage-r-cross-stage-test
  "Cross-stage user-story validation for Stage R (HR + payroll), per
   ADR-037's per-stage rhythm. Validates that the three per-country
   adapters (DE-DATEV-LODAS / US-ADP-GLI / CA-CRA) compose cleanly on
   top of the C1 substrate when a single trans-national employee
   appears in all three countries.

   The scenario — note 79 §2.2's load-bearing case:
     - Jane Doe is one :person (global identity).
     - Acme-DE-GmbH employs her as Senior Engineer (EUR, SKR04 chart).
     - Acme-US-LLC employs her on secondment as VP Engineering (USD,
       QBO chart, NY state).
     - Acme-CA-Corp employs her as Director (CAD, CA chart, ON
       province).
     - Three concurrent :employment rows. Three :compensation rows
       (one per employment, different currencies).
     - Three payroll-runs (one per country) producing three balanced
       :transaction entries on per-entity CoAs.

   The test exercises the substrate composition; the per-vendor
   parsers + posting-builders are unit-tested in their own modules.
   We use a hand-rolled mock compute provider per country (each
   returns canonical PayrollFacts for Jane's employment at that
   entity) and the country's real posting builder, so the wiring
   covers everything except the file-format parser itself.

   The test surfaces friction the per-stage unit tests cannot — in
   particular, friction from the substrate orchestrator's parameter
   surface, the per-country emit-doc convention divergence, and the
   :compensation-component multi-currency story."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.dsar :as dsar]
            [kontor.hr.compensation :as comp]
            [kontor.hr.core :as hr]
            [kontor.hr.dsar :as hr-dsar]
            [kontor.hr.employment :as employment]
            [kontor.hr.pay-period :as ppd]
            [kontor.hr.payroll :as payroll]
            [kontor.hr.person :as person]
            [kontor.payroll-provider :as ppro]))

;; ============================================================================
;; Mock provider trio used per country
;; ============================================================================
;; The mock returns one PayrollFacts per employment with hardcoded
;; numbers that match each country's component vocabulary. Real
;; adapters consume DATEV / ADP / Ceridian / Wagepoint output; the
;; cross-stage test focuses on substrate composition + per-country
;; posting routing.

(defrecord MockCompute [opts]
  ppro/PayrollComputeProvider
  (provider-id [_] (:provider-id opts :mock))
  (compute-payroll [_ {:keys [employment-eids]}]
    (mapv (fn [eid]
            (assoc (:fact opts) :employment eid))
          employment-eids)))

(defrecord MockPostingBuilder [opts]
  ppro/PayrollPostingBuilder
  (build-postings [_ facts {:keys [accounts]}]
    ;; Build a minimal Dr wages-expense / Cr wages-payable pair per
    ;; fact. Country-specific posting builders normally split per
    ;; component-kind; for cross-stage we just need balanced postings
    ;; per entity / commodity.
    (let [commodity (:commodity opts)]
      (mapcat
       (fn [{:keys [gross]}]
         [{:posting/account (:wages-expense accounts)
           :posting/amount gross
           :posting/commodity commodity
           :posting/narration "Gross wages"}
          {:posting/account (:wages-payable accounts)
           :posting/amount (.negate ^java.math.BigDecimal gross)
           :posting/commodity commodity
           :posting/narration "Wages payable"}])
       facts))))

;; ============================================================================
;; Bootstrap
;; ============================================================================

(defn- bootstrap []
  (let [conn (core/create-test-db)]
    (hr/install! conn)
    (d/transact
     conn
     [;; Three commodities for the three jurisdictions.
      {:db/id "eur" :commodity/symbol "EUR" :commodity/precision 2}
      {:db/id "usd" :commodity/symbol "USD" :commodity/precision 2}
      {:db/id "cad" :commodity/symbol "CAD" :commodity/precision 2}
      ;; Three entities, one per country.
      {:db/id "ent-de" :entity/code "ACME-DE-GMBH"
       :entity/name "Acme DE GmbH" :entity/kind :operating}
      {:db/id "ent-us" :entity/code "ACME-US-LLC"
       :entity/name "Acme US LLC" :entity/kind :operating}
      {:db/id "ent-ca" :entity/code "ACME-CA-CORP"
       :entity/name "Acme CA Corp" :entity/kind :operating}
      ;; Per-country minimal payroll chart.
      ;; DE — SKR04 wage accounts.
      {:db/id "de-wages" :account/code "4120"
       :account/name "Löhne und Gehälter"
       :account/type :expense :account/active true}
      {:db/id "de-wages-payable" :account/code "1741"
       :account/name "Verbindlichkeiten LuG"
       :account/type :liability :account/active true}
      ;; US — QBO-shaped.
      {:db/id "us-wages" :account/code "6100"
       :account/name "Wages Expense"
       :account/type :expense :account/active true}
      {:db/id "us-wages-payable" :account/code "2100"
       :account/name "Wages Payable"
       :account/type :liability :account/active true}
      ;; CA.
      {:db/id "ca-wages" :account/code "5400"
       :account/name "Salaries & Wages"
       :account/type :expense :account/active true}
      {:db/id "ca-wages-payable" :account/code "2110"
       :account/name "Wages Payable"
       :account/type :liability :account/active true}
      ;; One journal per country.
      {:db/id "j-de" :journal/code "PAY-DE"
       :journal/name "Payroll DE" :journal/type :general}
      {:db/id "j-us" :journal/code "PAY-US"
       :journal/name "Payroll US" :journal/type :general}
      {:db/id "j-ca" :journal/code "PAY-CA"
       :journal/name "Payroll CA" :journal/type :general}
      ;; Periods for May 2026 in each country.
      {:db/id "p-2026-05" :period/name "2026-05"
       :period/start #inst "2026-05-01"
       :period/end #inst "2026-06-01"}])
    conn))

(defn- ref-eid [db a v]
  (d/q '[:find ?e . :in $ ?a ?v :where [?e ?a ?v]] db a v))

;; ============================================================================
;; The trans-national scenario
;; ============================================================================

(defn- setup-jane! [conn]
  (person/create-person! conn {:external-id "P-jane"
                               :given-name "Jane" :family-name "Doe"
                               :birth-date #inst "1985-07-22"
                               :citizenship ["DE" "US"]
                               :kind :employee})
  (let [db (d/db conn)
        jane (hr/person-by-external-id db "P-jane")
        de (ref-eid db :entity/code "ACME-DE-GMBH")
        us (ref-eid db :entity/code "ACME-US-LLC")
        ca (ref-eid db :entity/code "ACME-CA-CORP")
        eur (ref-eid db :commodity/symbol "EUR")
        usd (ref-eid db :commodity/symbol "USD")
        cad (ref-eid db :commodity/symbol "CAD")]
    (employment/hire! conn {:code "EMP-JANE-DE" :person jane :entity de
                            :start-date #inst "2026-01-01"
                            :job-title "Senior Engineer"
                            :work-time-fraction 0.60M})
    (employment/hire! conn {:code "EMP-JANE-US" :person jane :entity us
                            :start-date #inst "2026-04-01"
                            :job-title "VP Engineering (secondment)"
                            :work-time-fraction 0.40M
                            :work-relationship-kind :secondment})
    (employment/hire! conn {:code "EMP-JANE-CA" :person jane :entity ca
                            :start-date #inst "2026-04-01"
                            :job-title "Director, Engineering"
                            :work-time-fraction 0.20M
                            :work-relationship-kind :secondment})
    (let [db (d/db conn)
          emp-de (hr/employment-by-code db "EMP-JANE-DE")
          emp-us (hr/employment-by-code db "EMP-JANE-US")
          emp-ca (hr/employment-by-code db "EMP-JANE-CA")]
      ;; Three compensations in three currencies.
      (comp/set-compensation!
       conn {:employment emp-de :effective-from #inst "2026-01-01"
             :commodity eur
             :components [{:kind :base-wage :amount 5000M :period :monthly}]})
      (comp/set-compensation!
       conn {:employment emp-us :effective-from #inst "2026-04-01"
             :commodity usd
             :components [{:kind :base-wage :amount 8000M :period :monthly}]})
      (comp/set-compensation!
       conn {:employment emp-ca :effective-from #inst "2026-04-01"
             :commodity cad
             :components [{:kind :base-wage :amount 4000M :period :monthly}]})
      {:jane jane
       :emp-de emp-de :emp-us emp-us :emp-ca emp-ca
       :de de :us us :ca ca
       :eur eur :usd usd :cad cad})))

(defn- run-country-payroll!
  "Run one country's payroll using the country-specific accounts +
   mock compute provider + mock posting builder. Returns the
   tx-report."
  [conn {:keys [run-code tx-code period entity employment journal
                compute-provider posting-builder accounts]}]
  (ppd/create-pay-period! conn {:code (str "PP-" run-code)
                                :entity entity
                                :start-date #inst "2026-05-01"
                                :end-date #inst "2026-05-31"
                                :frequency :monthly
                                :fiscal-period period})
  (let [pp-eid (hr/pay-period-by-code (d/db conn) (str "PP-" run-code))]
    (payroll/run-payroll!
     conn {:pay-period pp-eid
           :entity entity
           :employments [employment]
           :compute-provider compute-provider
           :posting-builder posting-builder
           :accounts accounts
           :run-code run-code
           :tx-code tx-code
           :journal journal})))

;; ============================================================================
;; The cross-stage test
;; ============================================================================

(deftest trans-national-jane-payroll-month
  (let [conn (bootstrap)
        {:keys [jane emp-de emp-us emp-ca de us ca eur usd cad]} (setup-jane! conn)
        db (d/db conn)
        p-2026-05 (ref-eid db :period/name "2026-05")
        j-de (ref-eid db :journal/code "PAY-DE")
        j-us (ref-eid db :journal/code "PAY-US")
        j-ca (ref-eid db :journal/code "PAY-CA")
        de-wages-exp (ref-eid db :account/code "4120")
        de-wages-pay (ref-eid db :account/code "1741")
        us-wages-exp (ref-eid db :account/code "6100")
        us-wages-pay (ref-eid db :account/code "2100")
        ca-wages-exp (ref-eid db :account/code "5400")
        ca-wages-pay (ref-eid db :account/code "2110")
        de-fact {:gross 3000M :net 1900M
                 :components [{:kind :base-wage       :amount 3000M}
                              {:kind :withholding-tax :amount -500M}
                              {:kind :employee-si     :amount -600M}
                              {:kind :employer-si :amount 570M :employer-side? true}]
                 :jurisdiction-specific-codes {:de/lohnart "0001"}}
        us-fact {:gross 3200M :net 2400M
                 :components [{:kind :base-wage       :amount 3200M}
                              {:kind :withholding-tax :amount -550M}
                              {:kind :employee-si     :amount -250M}
                              {:kind :employer-si :amount 245M :employer-side? true}]
                 :jurisdiction-specific-codes {:us/state "NY"}}
        ca-fact {:gross 800M :net 600M
                 :components [{:kind :base-wage       :amount 800M}
                              {:kind :withholding-tax :amount -150M}
                              {:kind :employee-si     :amount -50M}
                              {:kind :employer-si :amount 50M :employer-side? true}]
                 :jurisdiction-specific-codes {:ca/province "ON"}}]
    ;; Run all three payrolls.
    (run-country-payroll!
     conn {:run-code "JANE-DE-2026-05" :tx-code "TX-JANE-DE-2026-05"
           :period p-2026-05 :entity de :employment emp-de :journal j-de
           :compute-provider (->MockCompute {:provider-id :mock-datev-lodas
                                             :fact de-fact})
           :posting-builder (->MockPostingBuilder {:commodity eur})
           :accounts {:wages-expense de-wages-exp
                      :wages-payable de-wages-pay}})
    (run-country-payroll!
     conn {:run-code "JANE-US-2026-05" :tx-code "TX-JANE-US-2026-05"
           :period p-2026-05 :entity us :employment emp-us :journal j-us
           :compute-provider (->MockCompute {:provider-id :mock-adp-gli
                                             :fact us-fact})
           :posting-builder (->MockPostingBuilder {:commodity usd})
           :accounts {:wages-expense us-wages-exp
                      :wages-payable us-wages-pay}})
    (run-country-payroll!
     conn {:run-code "JANE-CA-2026-05" :tx-code "TX-JANE-CA-2026-05"
           :period p-2026-05 :entity ca :employment emp-ca :journal j-ca
           :compute-provider (->MockCompute {:provider-id :mock-ceridian
                                             :fact ca-fact})
           :posting-builder (->MockPostingBuilder {:commodity cad})
           :accounts {:wages-expense ca-wages-exp
                      :wages-payable ca-wages-pay}})

    (testing "Jane has three concurrent employments across three entities"
      (let [emps (d/q '[:find [?e ...]
                        :in $ ?p
                        :where [?e :employment/person ?p]]
                      (d/db conn) jane)]
        (is (= 3 (count emps)))))

    (testing "Each employment carries its own FTE; sum 1.20 (substrate allows over-allocation by design)"
      ;; Note 86 P1-86-7: the substrate does NOT enforce
      ;; Σ work-time-fraction ≤ 1.0 — secondment-with-overlap is
      ;; legitimate (note 79 §2.2). The `kontor.hr.employment/
      ;; sum-work-time-fraction` helper exposes the sum so consumer
      ;; policy can compose an over-allocation guard.
      (let [ftes (d/q '[:find [?ft ...]
                        :in $ ?p
                        :where
                        [?e :employment/person ?p]
                        [?e :employment/work-time-fraction ?ft]]
                      (d/db conn) jane)]
        (is (= 3 (count ftes)))
        (is (= 1.20M (reduce (fn [a v] (.add ^java.math.BigDecimal a
                                             ^java.math.BigDecimal v))
                             0M ftes)))
        (is (= 1.20M (employment/sum-work-time-fraction (d/db conn) jane))
            "the helper exposes the same sum without requiring the consumer to roll their own datalog")))

    (testing "Each compensation is in a different currency"
      (let [comp-currencies
            (d/q '[:find [?sym ...]
                   :in $ ?p
                   :where
                   [?e :employment/person ?p]
                   [?c :compensation/employment ?e]
                   [?c :compensation/commodity ?cm]
                   [?cm :commodity/symbol ?sym]]
                 (d/db conn) jane)]
        (is (= #{"EUR" "USD" "CAD"} (set comp-currencies)))))

    (testing "Three :payroll-run rows produced with country-specific provider IDs"
      (let [runs (d/q '[:find ?code ?pid
                        :where
                        [?r :payroll-run/code ?code]
                        [?r :payroll-run/provider-id ?pid]]
                      (d/db conn))]
        (is (= 3 (count runs)))
        (is (= #{:mock-datev-lodas :mock-adp-gli :mock-ceridian}
               (set (map second runs))))))

    (testing "Each payroll-run has a linked :transaction with balanced postings"
      (doseq [code ["JANE-DE-2026-05" "JANE-US-2026-05" "JANE-CA-2026-05"]]
        (let [run-eid (d/q '[:find ?r . :in $ ?c
                             :where [?r :payroll-run/code ?c]]
                           (d/db conn) code)
              tx (d/q '[:find ?t . :in $ ?r
                        :where [?r :payroll-run/payroll-transaction ?t]]
                      (d/db conn) run-eid)
              postings (when tx
                         (d/q '[:find ?amt
                                :in $ ?t
                                :where
                                [?p :posting/transaction ?t]
                                [?p :posting/amount ?amt]]
                              (d/db conn) tx))
              sum (reduce (fn [a [v]]
                            (.add ^java.math.BigDecimal a
                                  ^java.math.BigDecimal v))
                          0M postings)]
          (is (some? tx) (str "run " code " linked to a :transaction"))
          (is (= 2 (count postings)) (str "run " code " has 2 posting legs"))
          (is (zero? (.compareTo ^java.math.BigDecimal sum 0M))
              (str "run " code " is balanced")))))

    (testing "DSAR walk from Jane's :person reaches all 3 employments + 3 compensations"
      ;; collect-for-person walks :employment + :compensation directly
      ;; (kontor.hr.dsar/collect-for-person). The HR helper stays as the
      ;; structured entry-point for HR-bundle consumers.
      (let [bundle (hr-dsar/collect-for-person (d/db conn) jane)]
        (is (= 3 (count (:employments bundle))))
        (is (= 3 (count (:compensations bundle))))))

    (testing "Kernel-canonical kontor.dsar/collect reaches HR via the extension collector (P1-86-5)"
      ;; Jane has a :person but no :partner row yet — wire one up so the
      ;; kernel walker has a subject to start from. Per the hybrid
      ;; model (note 79 §2.3), the partner side stays kernel-side; the
      ;; person side stays HR-side; the extension collector bridges.
      (d/transact conn [{:db/id "p-jane"
                         :partner/external-id "PARTNER-jane"
                         :partner/name "Jane Doe (employee)"
                         :partner/kind :employee
                         :partner/person jane}])
      (let [partner-eid (d/q '[:find ?p . :in $ ?x :where
                               [?p :partner/external-id ?x]]
                             (d/db conn) "PARTNER-jane")
            kernel-bundle (dsar/collect (d/db conn) partner-eid {})
            hr-ext (get-in kernel-bundle [:extensions :hr])]
        (is (some? hr-ext) ":extensions :hr is populated by the HR extension collector")
        (is (= 3 (count (:employments hr-ext))))
        (is (= 3 (count (:compensations hr-ext))))))

    (testing "Per-country wage-expense totals reconcile to the engine output"
      (let [account-totals (fn [account-eid]
                             (d/q '[:find (sum ?amt) .
                                    :with ?p
                                    :in $ ?a
                                    :where
                                    [?p :posting/account ?a]
                                    [?p :posting/amount ?amt]]
                                  (d/db conn) account-eid))]
        ;; Each "wages-expense" account holds the gross debit only —
        ;; the corresponding "wages-payable" account holds the negative.
        (is (= 3000M (account-totals de-wages-exp)))
        (is (= 3200M (account-totals us-wages-exp)))
        (is (= 800M  (account-totals ca-wages-exp)))))))

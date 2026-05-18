(ns kontor.hr.hr-test
  "Stage R C1 substrate end-to-end test (ADR-075).

   Covers:
     - install! is idempotent
     - create-person! / hire! shape
     - hire-with-contract-doc (the audit-doc/category seam)
     - multi-employment per person (one person, two entities)
     - set-compensation! + employment-current-wage
     - supersede-compensation! (closes prior + creates new)
     - bitemporal wage knob (set forward-dated comp; query past/future)
     - terminate!
     - run-payroll! with a hand-written mock provider trio
       end-to-end (compute → build → post → run row)
     - check-facts sum invariant rejects bad facts"
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.hr.compensation :as comp]
            [kontor.hr.core :as hr]
            [kontor.hr.employment :as employment]
            [kontor.hr.pay-period :as pp]
            [kontor.hr.payroll :as payroll]
            [kontor.hr.person :as person]
            [kontor.hr.schema :as hr-schema]
            [kontor.payroll-provider :as ppro]))

;; ============================================================================
;; Fixture
;; ============================================================================

(defn- bootstrap []
  (let [conn (core/create-test-db)]
    (hr/install! conn)
    (d/transact conn
                [{:db/id "eur" :commodity/symbol "EUR" :commodity/precision 2}
                 {:db/id "usd" :commodity/symbol "USD" :commodity/precision 2}
                 ;; Two entities — DE GmbH and US LLC — for multi-employment.
                 {:db/id "ent-de" :entity/code "DE-GMBH" :entity/name "Acme DE GmbH"
                  :entity/kind :operating}
                 {:db/id "ent-us" :entity/code "US-LLC"  :entity/name "Acme US LLC"
                  :entity/kind :operating}
                 ;; GL accounts — minimal DE chart.
                 {:db/id "acct-wages-de" :account/code "4120"
                  :account/name "Löhne und Gehälter"
                  :account/type :expense :account/active true}
                 {:db/id "acct-wages-payable-de" :account/code "1741"
                  :account/name "Verbindlichkeiten LuG"
                  :account/type :liability :account/active true}
                 ;; Journal + period (kernel-required by build-transaction).
                 {:db/id "journal-payroll-de" :journal/code "PAY-DE"
                  :journal/name "Payroll (DE)" :journal/type :general}
                 {:db/id "period-2026-05" :period/name "2026-05"
                  :period/start #inst "2026-05-01"
                  :period/end #inst "2026-06-01"}
                 ;; A contract audit-doc, carrying the new :audit-doc/category
                 ;; (ADR-075).
                 {:db/id "doc-contract-1" :audit-doc/code "CONTRACT-jane"
                  :audit-doc/type :uploaded-pdf
                  :audit-doc/storage-uri "s3://contracts/jane-de.pdf"
                  :audit-doc/uploaded-at #inst "2026-04-15"
                  :audit-doc/category :hr-personnel}])
    conn))

(defn- ref-eid [db a v]
  (d/q '[:find ?e . :in $ ?a ?v :where [?e ?a ?v]] db a v))

;; ============================================================================
;; Schema + install
;; ============================================================================

(deftest install-is-idempotent
  (let [conn (bootstrap)]
    (testing "second install! does not throw"
      (is (any? (hr/install! conn))))
    (testing "schema attrs survive the second install"
      (is (some? (d/q '[:find ?a . :in $ ?ident
                        :where [?a :db/ident ?ident]]
                      (d/db conn) :person/external-id))))))

(deftest schema-attrs-present
  (let [conn (bootstrap)
        db (d/db conn)]
    (testing "kontor-hr attrs are queryable"
      (is (some? (d/q '[:find ?a . :in $ ?ident
                        :where [?a :db/ident ?ident]]
                      db :person/external-id)))
      (is (some? (d/q '[:find ?a . :in $ ?ident
                        :where [?a :db/ident ?ident]]
                      db :employment/work-time-fraction)))
      (is (some? (d/q '[:find ?a . :in $ ?ident
                        :where [?a :db/ident ?ident]]
                      db :compensation-component/account-hint))))
    (testing "kernel ADR-075 attrs present + category attrs accept tags"
      (let [doc-eid (ref-eid db :audit-doc/code "CONTRACT-jane")]
        (is (= :hr-personnel
               (:audit-doc/category (d/pull db [:audit-doc/category] doc-eid))))))))

;; ============================================================================
;; create-person + hire
;; ============================================================================

(deftest create-person-and-hire
  (let [conn (bootstrap)
        _ (person/create-person! conn {:external-id "P-jane"
                                       :given-name "Jane"
                                       :family-name "Doe"
                                       :birth-date #inst "1992-03-14"
                                       :citizenship ["DE"]})
        db (d/db conn)
        jane (hr/person-by-external-id db "P-jane")
        ent-de (ref-eid db :entity/code "DE-GMBH")
        contract (ref-eid db :audit-doc/code "CONTRACT-jane")
        _ (employment/hire! conn {:code "EMP-DE-jane"
                                  :person jane
                                  :entity ent-de
                                  :start-date #inst "2026-05-01"
                                  :job-title "Senior Engineer"
                                  :work-time-fraction 1M
                                  :contract-doc contract})
        db (d/db conn)
        emp (d/pull db '[* {:employment/person [*]
                            :employment/entity [:entity/code]
                            :employment/contract-doc [:audit-doc/code
                                                      :audit-doc/category]}]
                    (hr/employment-by-code db "EMP-DE-jane"))]
    (testing "person attrs round-trip"
      (is (= "Jane" (-> emp :employment/person :person/given-name)))
      (is (= "Doe"  (-> emp :employment/person :person/family-name)))
      (is (= ["DE"] (vec (-> emp :employment/person :person/citizenship)))))
    (testing "employment defaults are populated"
      (is (= :hired (:employment/state emp)))
      (is (= 1M     (:employment/work-time-fraction emp)))
      (is (= :standard (:employment/work-relationship-kind emp)))
      (is (= "DE-GMBH" (-> emp :employment/entity :entity/code))))
    (testing "contract doc is linked with the kernel-side :hr-personnel category"
      (is (= "CONTRACT-jane" (-> emp :employment/contract-doc :audit-doc/code)))
      (is (= :hr-personnel   (-> emp :employment/contract-doc :audit-doc/category))))))

(deftest multi-employment-per-person
  (let [conn (bootstrap)
        _ (person/create-person! conn {:external-id "P-jane"
                                       :given-name "Jane" :family-name "Doe"})
        db (d/db conn)
        jane (hr/person-by-external-id db "P-jane")
        de (ref-eid db :entity/code "DE-GMBH")
        us (ref-eid db :entity/code "US-LLC")
        _ (employment/hire! conn {:code "EMP-DE-jane" :person jane :entity de
                                  :start-date #inst "2026-05-01"
                                  :job-title "Senior Eng (DE)"})
        _ (employment/hire! conn {:code "EMP-US-jane" :person jane :entity us
                                  :start-date #inst "2026-05-01"
                                  :job-title "Secondment lead (US)"
                                  :work-time-fraction 0.40M})]
    (testing "one person has two concurrent employments"
      (is (= 2 (count (d/q '[:find [?e ...]
                             :in $ ?p
                             :where [?e :employment/person ?p]]
                           (d/db conn) jane)))))
    (testing "each employment carries its own FTE"
      (let [us-emp (d/pull (d/db conn)
                           [:employment/work-time-fraction
                            :employment/job-title]
                           (hr/employment-by-code (d/db conn) "EMP-US-jane"))]
        (is (= 0.40M (:employment/work-time-fraction us-emp)))
        (is (= "Secondment lead (US)" (:employment/job-title us-emp)))))))

;; ============================================================================
;; Compensation
;; ============================================================================

(deftest set-and-query-compensation
  (let [conn (bootstrap)
        _ (person/create-person! conn {:external-id "P-jane"
                                       :given-name "Jane" :family-name "Doe"})
        db (d/db conn)
        jane (hr/person-by-external-id db "P-jane")
        de (ref-eid db :entity/code "DE-GMBH")
        eur (ref-eid db :commodity/symbol "EUR")
        _ (employment/hire! conn {:code "EMP-DE-jane" :person jane :entity de
                                  :start-date #inst "2026-05-01"})
        emp-eid (hr/employment-by-code (d/db conn) "EMP-DE-jane")
        _ (comp/set-compensation!
           conn {:employment emp-eid
                 :effective-from #inst "2026-05-01"
                 :commodity eur
                 :components [{:kind :base-wage   :amount 5000M :period :monthly}
                              {:kind :vwl         :amount 40M   :period :monthly
                               :account-hint :vwl}
                              {:kind :employer-si :amount 950M  :period :monthly}]})]
    (testing "current-compensation finds the active envelope"
      (is (some? (comp/current-compensation (d/db conn) emp-eid))))
    (testing "employment-current-wage sums :base-wage"
      (is (= 5000M (comp/employment-current-wage (d/db conn) emp-eid))))
    (testing "components are queryable"
      (let [c (comp/current-compensation (d/db conn) emp-eid)
            comps (comp/components-of (d/db conn) c)]
        (is (= 3 (count comps)))
        (is (= #{:base-wage :vwl :employer-si}
               (set (map :compensation-component/kind comps))))))))

(deftest supersede-compensation-closes-prior
  (let [conn (bootstrap)
        _ (person/create-person! conn {:external-id "P-jane"
                                       :given-name "Jane" :family-name "Doe"})
        db (d/db conn)
        jane (hr/person-by-external-id db "P-jane")
        de (ref-eid db :entity/code "DE-GMBH")
        eur (ref-eid db :commodity/symbol "EUR")
        _ (employment/hire! conn {:code "EMP-DE-jane" :person jane :entity de
                                  :start-date #inst "2026-05-01"})
        emp-eid (hr/employment-by-code (d/db conn) "EMP-DE-jane")
        _ (comp/set-compensation!
           conn {:employment emp-eid
                 :effective-from #inst "2026-05-01"
                 :commodity eur
                 :components [{:kind :base-wage :amount 5000M :period :monthly}]})
        _ (comp/supersede-compensation!
           conn {:employment emp-eid
                 :effective-from #inst "2027-01-01"
                 :commodity eur
                 :components [{:kind :base-wage :amount 5500M :period :monthly}]})]
    (testing "only one :compensation is :active for the employment"
      (let [actives (d/q '[:find [?c ...]
                           :in $ ?emp
                           :where [?c :compensation/employment ?emp]
                           [?c :compensation/state :active]]
                         (d/db conn) emp-eid)]
        (is (= 1 (count actives)))))
    (testing "current wage at 2026-12-01 is the old 5000; at 2027-02-01 is the new 5500"
      (is (= 5000M (comp/employment-current-wage (d/db conn) emp-eid
                                                 #inst "2026-12-01")))
      (is (= 5500M (comp/employment-current-wage (d/db conn) emp-eid
                                                 #inst "2027-02-01"))))))

;; ============================================================================
;; check-facts sum invariant
;; ============================================================================

;; ============================================================================
;; terminate! routes through the status machine (P0-85-1 regression)
;; ============================================================================

(deftest terminate-rejects-missing-supporting-doc
  (let [conn (bootstrap)
        _ (person/create-person! conn {:external-id "P-bob"
                                       :given-name "Bob" :family-name "Smith"})
        db (d/db conn)
        bob (hr/person-by-external-id db "P-bob")
        de (ref-eid db :entity/code "DE-GMBH")
        _ (employment/hire! conn {:code "EMP-bob" :person bob :entity de
                                  :start-date #inst "2026-05-01"})
        emp-eid (hr/employment-by-code (d/db conn) "EMP-bob")
        ;; Move :hired → :active so termination is a legal transition.
        _ (d/transact conn [[:db/add emp-eid :employment/state :active]])]
    (testing "terminate! without :supporting-doc throws before any write"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"supporting-doc required"
           (employment/terminate! conn {:employment emp-eid
                                        :end-date #inst "2026-06-30"
                                        :reason :voluntary
                                        :changed-by-uid bob})))
      (is (= :active (:employment/state
                      (d/pull (d/db conn) [:employment/state] emp-eid)))
          ":employment/state stays :active when termination is rejected"))))

(deftest terminate-with-supporting-doc-writes-status-history
  (let [conn (bootstrap)
        _ (person/create-person! conn {:external-id "P-carol"
                                       :given-name "Carol" :family-name "Jones"})
        db (d/db conn)
        carol (hr/person-by-external-id db "P-carol")
        de (ref-eid db :entity/code "DE-GMBH")
        contract (ref-eid db :audit-doc/code "CONTRACT-jane")
        _ (employment/hire! conn {:code "EMP-carol" :person carol :entity de
                                  :start-date #inst "2026-05-01"})
        emp-eid (hr/employment-by-code (d/db conn) "EMP-carol")
        _ (d/transact conn [[:db/add emp-eid :employment/state :active]])
        ;; Carol can't approve her own termination.
        approver (d/q '[:find ?p . :in $ ?x :where
                        [?p :person/external-id ?x]]
                      (d/db conn) "P-bob")
        _ (when-not approver
            (person/create-person! conn {:external-id "P-mgr"
                                         :given-name "Mgr" :family-name "X"}))
        mgr (or approver (hr/person-by-external-id (d/db conn) "P-mgr"))
        _ (employment/terminate! conn {:employment emp-eid
                                       :end-date #inst "2026-06-30"
                                       :reason :voluntary
                                       :supporting-doc contract
                                       :changed-by-uid mgr})]
    (testing ":employment/state is :terminated + :end-date written"
      (let [emp (d/pull (d/db conn) [:employment/state
                                     :employment/end-date
                                     :employment/termination-reason]
                        emp-eid)]
        (is (= :terminated (:employment/state emp)))
        (is (= #inst "2026-06-30" (:employment/end-date emp)))
        (is (= :voluntary (:employment/termination-reason emp)))))
    (testing ":status-history row written by the status machine"
      (let [history (d/q '[:find [?h ...]
                           :in $ ?e
                           :where
                           [?h :status-history/entity ?e]
                           [?h :status-history/to :terminated]]
                         (d/db conn) emp-eid)]
        (is (= 1 (count history))
            "exactly one :terminated history row per employment")))))

(deftest check-facts-passes-balanced-fact
  (let [fact {:employment "x"
              :gross 5000M
              :net 4000M
              :components [{:kind :base-wage      :amount 5000M}
                           {:kind :withholding-tax :amount -700M}
                           {:kind :employee-si     :amount -300M}
                           {:kind :employer-si     :amount 900M :employer-side? true}]}]
    (is (= fact (payroll/check-facts fact)))))

(deftest check-facts-rejects-imbalanced-net
  (let [fact {:employment "x"
              :gross 5000M
              :net 5000M  ;; wrong: should be 4000
              :components [{:kind :base-wage      :amount 5000M}
                           {:kind :withholding-tax :amount -1000M}]}]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"net !="
                          (payroll/check-facts fact)))))

;; ============================================================================
;; run-payroll! end-to-end with a mock provider trio
;; ============================================================================
;; Mock compute provider: returns one fact per employment with
;; hardcoded numbers (real adapters consume DATEV / ADP output).

(defrecord MockCompute [opts]
  ppro/PayrollComputeProvider
  (provider-id [_] :mock)
  (compute-payroll [_ {:keys [employment-eids]}]
    (mapv (fn [eid]
            {:employment eid
             :gross 5000M
             :net 4000M
             :components [{:kind :base-wage       :amount 5000M}
                          {:kind :withholding-tax :amount -700M}
                          {:kind :employee-si     :amount -300M}
                          {:kind :employer-si     :amount 950M
                           :employer-side? true}]
             :jurisdiction-specific-codes {}})
          employment-eids)))

(defrecord MockPostingBuilder [opts]
  ppro/PayrollPostingBuilder
  (build-postings [_ facts {:keys [accounts]}]
    (let [eur-eid (:eur-eid opts)]
      ;; One per fact: Dr wages-expense / Cr wages-payable for the net.
      ;; Real builders split per component-kind across many accounts.
      (mapcat
       (fn [{:keys [gross net]}]
         [{:posting/account (:wages-expense accounts)
           :posting/amount gross
           :posting/commodity eur-eid
           :posting/narration "Payroll wages (gross)"}
          {:posting/account (:wages-payable accounts)
           :posting/amount (.negate ^java.math.BigDecimal gross)
           :posting/commodity eur-eid
           :posting/narration "Wages payable (gross)"}])
       facts))))

(deftest run-payroll-end-to-end
  (let [conn (bootstrap)
        _ (person/create-person! conn {:external-id "P-jane"
                                       :given-name "Jane" :family-name "Doe"})
        db (d/db conn)
        jane (hr/person-by-external-id db "P-jane")
        de (ref-eid db :entity/code "DE-GMBH")
        eur (ref-eid db :commodity/symbol "EUR")
        period (ref-eid db :period/name "2026-05")
        journal (ref-eid db :journal/code "PAY-DE")
        wages-exp (ref-eid db :account/code "4120")
        wages-pay (ref-eid db :account/code "1741")
        _ (employment/hire! conn {:code "EMP-DE-jane" :person jane :entity de
                                  :start-date #inst "2026-05-01"})
        emp-eid (hr/employment-by-code (d/db conn) "EMP-DE-jane")
        _ (pp/create-pay-period! conn {:code "DE-2026-05" :entity de
                                       :start-date #inst "2026-05-01"
                                       :end-date #inst "2026-05-31"
                                       :frequency :monthly
                                       :fiscal-period period})
        pp-eid (hr/pay-period-by-code (d/db conn) "DE-2026-05")
        compute-provider (->MockCompute {})
        posting-builder (->MockPostingBuilder {:eur-eid eur})
        report (payroll/run-payroll!
                conn {:pay-period pp-eid
                      :entity de
                      :employments [emp-eid]
                      :compute-provider compute-provider
                      :posting-builder posting-builder
                      :accounts {:wages-expense wages-exp
                                 :wages-payable wages-pay}
                      :run-code "RUN-DE-2026-05-001"
                      :tx-code "TX-PAYROLL-DE-2026-05"
                      :journal journal
                      :commodity eur})
        db (:db-after report)
        run-eid (d/q '[:find ?r . :in $ ?c
                       :where [?r :payroll-run/code ?c]]
                     db "RUN-DE-2026-05-001")
        run (d/pull db '[* {:payroll-run/payroll-transaction
                            [:transaction/external-id
                             {:posting/_transaction [:posting/amount
                                                     :posting/account]}]}]
                    run-eid)]
    (testing "payroll-run row is created with control totals"
      (is (= 5000M (:payroll-run/control-total-gross run)))
      (is (= 4000M (:payroll-run/control-total-net   run)))
      (is (= :mock (:payroll-run/provider-id run)))
      (is (= :computed (:payroll-run/state run))))
    (testing "the linked :transaction has two posting legs that sum to zero"
      (let [tx (:payroll-run/payroll-transaction run)
            postings (:posting/_transaction tx)
            sum (reduce (fn [a {:keys [posting/amount]}]
                          (.add ^java.math.BigDecimal a
                                ^java.math.BigDecimal amount))
                        0M postings)]
        (is (= "TX-PAYROLL-DE-2026-05" (:transaction/external-id tx)))
        (is (= 2 (count postings)))
        (is (zero? (.compareTo ^java.math.BigDecimal sum 0M)))))))

;; ============================================================================
;; run-payroll! links emit-docs via :payroll-run/emit-docs (P0-86-1)
;; ============================================================================
;; Note 86 P0-86-1: the substrate orchestrator must populate
;; :payroll-run/emit-docs with every doc the EmitProvider produced.
;; Two-doc provider exercises the cardinality/many path.

(defrecord TwoDocEmit [opts]
  ppro/PayrollEmitProvider
  (emit-payroll-events [_ _facts {:keys [pay-period-eid]}]
    [{:audit-doc/code (str "EMIT-A-" pay-period-eid)
      :audit-doc/type :emit-payload
      :audit-doc/category :payroll-filing
      :audit-doc/storage-uri "file:///tmp/a.txt"
      :audit-doc/uploaded-at (java.util.Date.)}
     {:audit-doc/code (str "EMIT-B-" pay-period-eid)
      :audit-doc/type :emit-payload
      :audit-doc/category :payroll-filing
      :audit-doc/storage-uri "file:///tmp/b.txt"
      :audit-doc/uploaded-at (java.util.Date.)}]))

(deftest run-payroll-links-emit-docs
  (let [conn (bootstrap)
        _ (person/create-person! conn {:external-id "P-jane"
                                       :given-name "Jane" :family-name "Doe"})
        db (d/db conn)
        jane (hr/person-by-external-id db "P-jane")
        de (ref-eid db :entity/code "DE-GMBH")
        eur (ref-eid db :commodity/symbol "EUR")
        period (ref-eid db :period/name "2026-05")
        journal (ref-eid db :journal/code "PAY-DE")
        wages-exp (ref-eid db :account/code "4120")
        wages-pay (ref-eid db :account/code "1741")
        _ (employment/hire! conn {:code "EMP-DE-jane" :person jane :entity de
                                  :start-date #inst "2026-05-01"})
        emp-eid (hr/employment-by-code (d/db conn) "EMP-DE-jane")
        _ (pp/create-pay-period! conn {:code "DE-2026-05" :entity de
                                       :start-date #inst "2026-05-01"
                                       :end-date #inst "2026-05-31"
                                       :frequency :monthly
                                       :fiscal-period period})
        pp-eid (hr/pay-period-by-code (d/db conn) "DE-2026-05")
        report (payroll/run-payroll!
                conn {:pay-period pp-eid
                      :entity de
                      :employments [emp-eid]
                      :compute-provider (->MockCompute {})
                      :posting-builder (->MockPostingBuilder {:eur-eid eur})
                      :emit-provider (->TwoDocEmit {})
                      :accounts {:wages-expense wages-exp
                                 :wages-payable wages-pay}
                      :run-code "RUN-EMIT-001"
                      :tx-code "TX-EMIT-001"
                      :journal journal
                      :commodity eur})
        db (:db-after report)
        run-eid (d/q '[:find ?r . :in $ ?c
                       :where [?r :payroll-run/code ?c]]
                     db "RUN-EMIT-001")
        run (d/pull db [{:payroll-run/emit-docs [:audit-doc/code
                                                 :audit-doc/category]}]
                    run-eid)
        emit-docs (:payroll-run/emit-docs run)]
    (testing ":payroll-run/emit-docs has both emit-provider outputs"
      (is (= 2 (count emit-docs)))
      (is (= #{:payroll-filing} (set (map :audit-doc/category emit-docs))))
      (is (= #{"EMIT-A" "EMIT-B"}
             (set (map #(subs (:audit-doc/code %) 0 6) emit-docs)))))))

;; ============================================================================
;; Back-dated :compensation correction — P1-86-6 bitemporal exercise
;; ============================================================================
;; Note 86 P1-86-6: kontor's flagship value proposition is bitemporal
;; correction. This test exercises the back-dated correction story
;; through the HR substrate: an envelope set with effective-from
;; 2026-01-01 at 5000M, the engine reports a corrected number for the
;; same window months later (4500M). A NEW envelope written at vt
;; 2026-01-01 represents "what we believe TODAY about 2026-01"; the
;; bitemporal substrate retains both views for the audit chain.

(deftest back-dated-compensation-correction
  (let [conn (bootstrap)
        _ (person/create-person! conn {:external-id "P-jane"
                                       :given-name "Jane" :family-name "Doe"})
        db (d/db conn)
        jane (hr/person-by-external-id db "P-jane")
        de (ref-eid db :entity/code "DE-GMBH")
        eur (ref-eid db :commodity/symbol "EUR")
        _ (employment/hire! conn {:code "EMP-DE-jane" :person jane :entity de
                                  :start-date #inst "2026-01-01"})
        emp-eid (hr/employment-by-code (d/db conn) "EMP-DE-jane")
        ;; Initial transaction at #inst "2026-01-15" — wage 5000M.
        _ (comp/set-compensation!
           conn {:employment emp-eid
                 :effective-from #inst "2026-01-01"
                 :commodity eur
                 :components [{:kind :base-wage :amount 5000M :period :monthly}]})
        ;; Capture the tx that wrote the original envelope (the "as of
        ;; when we BELIEVED" axis).
        db-after-original (d/db conn)
        ;; Months later (#inst "2026-04-30"), the engine reports that
        ;; Jane's actual wage in 2026-01 should have been 4500M. A
        ;; back-dated correction: supersede the original envelope with
        ;; a new envelope at the same effective-from + the new amount.
        _ (comp/supersede-compensation!
           conn {:employment emp-eid
                 :effective-from #inst "2026-01-01"  ; same effective date
                 :commodity eur
                 :components [{:kind :base-wage :amount 4500M :period :monthly}]})
        db-current (d/db conn)]
    (testing "TODAY's view: current wage at 2026-01-01 is the corrected 4500M"
      (is (= 4500M (comp/employment-current-wage db-current emp-eid
                                                 #inst "2026-01-15"))))
    (testing "AS-OF the original tx, the wage was 5000M (the historical view)"
      ;; d/as-of rewinds to the BELIEVED-IN-THE-PAST state. This is the
      ;; "what did we know when we filed the original return?" axis that
      ;; the substrate provides for audit-chain integrity.
      (is (= 5000M (comp/employment-current-wage db-after-original emp-eid
                                                 #inst "2026-01-15"))))
    (testing "Both envelopes are queryable simultaneously"
      ;; The corrected envelope is :active; the original is :superseded.
      ;; Both rows EXIST in the current db — supersession sets the
      ;; effective-to + state on the prior, doesn't retract it. The
      ;; audit chain has both views.
      (let [all-comps (d/q '[:find [?c ...]
                             :in $ ?emp
                             :where [?c :compensation/employment ?emp]]
                           db-current emp-eid)
            states (d/q '[:find [?st ...]
                          :in $ ?emp
                          :where
                          [?c :compensation/employment ?emp]
                          [?c :compensation/state ?st]]
                        db-current emp-eid)]
        (is (= 2 (count all-comps)) "both envelopes coexist")
        (is (= #{:active :superseded} (set states)))))))

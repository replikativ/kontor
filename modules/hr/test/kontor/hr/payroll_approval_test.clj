(ns kontor.hr.payroll-approval-test
  "ADR-153 — the `:payroll-run` `:computed → :approved` edge.

   `kontor.hr.schema/approval-policy-seeds` calls this \"the load-bearing
   edge\" and puts `:no-self-approval` on it. Two things were true when
   ADR-150 made that rule fail CLOSED on a nil creator:

     1. `kontor.hr.payroll/run-payroll!` never stamped
        `:kontor.audit/create-uid` and had no option slot for one, so every
        payroll run in kontor carried a nil creator.
     2. NOTHING in the repo ever transitioned a run to `:approved`. No
        `approve-run!` existed, no test drove the edge, and the seeded
        policy was unreachable dead data.

   (2) is why (1) shipped green. The pair of tests below is the fix for
   both: every payroll approval in kontor-hr was refused, and the suite
   could not tell."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.actor :as actor]
            [kontor.core :as core]
            [kontor.hr.core :as hr]
            [kontor.hr.employment :as employment]
            [kontor.hr.pay-period :as pp]
            [kontor.hr.payroll :as payroll]
            [kontor.hr.person :as person]
            [kontor.provider.payroll-provider :as ppro]))

;; ============================================================================
;; Fixture — the minimum chart a payroll run needs, plus two actors
;; ============================================================================

(defrecord MockCompute []
  ppro/PayrollComputeProvider
  (provider-id [_] :mock)
  (compute-payroll [_ {:keys [employment-eids]}]
    (mapv (fn [eid]
            {:employment eid
             :gross 5000M
             :net 4000M
             :components [{:kind :base-wage        :amount 5000M}
                          {:kind :withholding-tax  :amount -700M}
                          {:kind :employee-si      :amount -300M}]
             :jurisdiction-specific-codes {}})
          employment-eids)))

(defrecord MockPostingBuilder [eur]
  ppro/PayrollPostingBuilder
  (build-postings [_ facts {:keys [accounts]}]
    (mapcat (fn [{:keys [gross]}]
              [{:kontor.posting/account (:wages-expense accounts)
                :kontor.posting/amount gross
                :kontor.posting/commodity eur}
               {:kontor.posting/account (:wages-payable accounts)
                :kontor.posting/amount (.negate ^java.math.BigDecimal gross)
                :kontor.posting/commodity eur}])
            facts)))

(defn- ref-eid [db a v]
  (d/q '[:find ?e . :in $ ?a ?v :where [?e ?a ?v]] db a v))

(defn- bootstrap []
  (let [conn (core/create-test-db)]
    (hr/install! conn)
    (actor/register-actors! conn [{:uid "payroll-clerk" :name "Petra P" :kind :person}
                                  {:uid "cfo"           :name "Carla C" :kind :person}])
    (d/transact conn
                [{:kontor.commodity/symbol "EUR" :kontor.commodity/precision 2}
                 {:kontor.entity/code "DE-GMBH" :kontor.entity/name "Acme DE GmbH"
                  :kontor.entity/kind :operating}
                 {:kontor.account/code "4120" :kontor.account/name "Löhne und Gehälter"
                  :kontor.account/type :expense :kontor.account/active true}
                 {:kontor.account/code "1741" :kontor.account/name "Verbindlichkeiten LuG"
                  :kontor.account/type :liability :kontor.account/active true}
                 {:kontor.journal/code "PAY-DE" :kontor.journal/name "Payroll (DE)"
                  :kontor.journal/type :general}
                 {:kontor.period/name "2026-05"
                  :kontor.period/start #inst "2026-05-01"
                  :kontor.period/end #inst "2026-06-01"}])
    conn))

(defn- run-a-payroll!
  "Compute one payroll run attributed to `actor`, and return its eid."
  [conn actor]
  (let [db (d/db conn)
        de (ref-eid db :kontor.entity/code "DE-GMBH")
        eur (ref-eid db :kontor.commodity/symbol "EUR")]
    (person/create-person! conn {:external-id "P-jane" :given-name "Jane"
                                 :family-name "Doe"})
    (employment/hire! conn {:code "EMP-jane"
                            :person (hr/person-by-external-id (d/db conn) "P-jane")
                            :entity de :start-date #inst "2026-05-01"})
    (pp/create-pay-period! conn {:code "DE-2026-05" :entity de
                                 :start-date #inst "2026-05-01"
                                 :end-date #inst "2026-05-31"
                                 :frequency :monthly
                                 :fiscal-period (ref-eid db :kontor.period/name "2026-05")})
    (payroll/run-payroll!
     conn {:pay-period (hr/pay-period-by-code (d/db conn) "DE-2026-05")
           :entity de
           :employments [(hr/employment-by-code (d/db conn) "EMP-jane")]
           :compute-provider (->MockCompute)
           :posting-builder (->MockPostingBuilder eur)
           :accounts {:wages-expense (ref-eid db :kontor.account/code "4120")
                      :wages-payable (ref-eid db :kontor.account/code "1741")}
           :run-code "RUN-2026-05"
           :tx-code "TX-PAY-2026-05"
           :journal (ref-eid db :kontor.journal/code "PAY-DE")
           :commodity eur
           :actor actor})
    (d/q '[:find ?r . :in $ ?c :where [?r :kontor.payroll-run/code ?c]]
         (d/db conn) "RUN-2026-05")))

;; ============================================================================
;; The pair
;; ============================================================================

(deftest a-payroll-run-can-be-approved-by-a-second-actor
  ;; THE test whose absence let the P0 ship. Everything else in the payroll
  ;; suite stops at :computed, so a control that refused 100% of approvals
  ;; was indistinguishable from one that worked.
  (let [conn (bootstrap)
        run  (run-a-payroll! conn "payroll-clerk")]
    (testing "the run records who ran it (there was no slot for this at all
              before ADR-153)"
      (is (some? (:kontor.audit/create-uid
                  (d/pull (d/db conn) [:kontor.audit/create-uid] run)))))
    (testing "the CFO — a different actor — can approve it"
      (payroll/approve-run! conn {:run run :actor "cfo"
                                  :reason-note "May 2026 payroll reviewed"})
      (is (= :approved (:kontor.payroll-run/state
                        (d/pull (d/db conn) [:kontor.payroll-run/state] run)))))
    (testing "and the approval left an audit-trail row naming the approver"
      (let [h (d/q '[:find [?h ...] :in $ ?r
                     :where
                     [?h :kontor.status-history/entity ?r]
                     [?h :kontor.status-history/facet :kontor.payroll-run/state]
                     [?h :kontor.status-history/to :approved]]
                   (d/db conn) run)]
        (is (= 1 (count h)))
        (is (= (actor/resolve-actor (d/db conn) "cfo")
               (:db/id (:kontor.status-history/changed-by-uid
                        (d/pull (d/db conn)
                                [{:kontor.status-history/changed-by-uid [:db/id]}]
                                (first h))))))))))

(deftest the-clerk-who-ran-the-payroll-cannot-approve-it
  (let [conn (bootstrap)
        run  (run-a-payroll! conn "payroll-clerk")]
    (testing "four-eyes on the load-bearing edge actually bites"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"(?i)approval-policy"
           (payroll/approve-run! conn {:run run :actor "payroll-clerk"}))))
    (is (= :computed (:kontor.payroll-run/state
                      (d/pull (d/db conn) [:kontor.payroll-run/state] run))))))

(deftest an-unattributed-approval-is-not-an-approval
  (let [conn (bootstrap)
        run  (run-a-payroll! conn "payroll-clerk")]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":actor required"
                          (payroll/approve-run! conn {:run run})))))

;; ============================================================================
;; The other half — the run must name its actor in the first place
;; ============================================================================

(deftest run-payroll-refuses-an-unattributed-run
  (let [conn (bootstrap)
        db (d/db conn)
        de (ref-eid db :kontor.entity/code "DE-GMBH")]
    (person/create-person! conn {:external-id "P-x" :given-name "X" :family-name "Y"})
    (employment/hire! conn {:code "EMP-x"
                            :person (hr/person-by-external-id (d/db conn) "P-x")
                            :entity de :start-date #inst "2026-05-01"})
    (pp/create-pay-period! conn {:code "PP-x" :entity de
                                 :start-date #inst "2026-05-01"
                                 :end-date #inst "2026-05-31"
                                 :frequency :monthly
                                 :fiscal-period (ref-eid db :kontor.period/name "2026-05")})
    (testing "refused UP FRONT, not silently at approval time six weeks later —
              a run with no recorded runner can never clear the seeded
              :no-self-approval gate, so producing one is never useful"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #":actor required"
           (payroll/run-payroll!
            conn {:pay-period (hr/pay-period-by-code (d/db conn) "PP-x")
                  :entity de
                  :employments [(hr/employment-by-code (d/db conn) "EMP-x")]
                  :compute-provider (->MockCompute)
                  :posting-builder (->MockPostingBuilder
                                    (ref-eid db :kontor.commodity/symbol "EUR"))
                  :accounts {}
                  :run-code "RUN-X" :tx-code "TX-X"}))))))

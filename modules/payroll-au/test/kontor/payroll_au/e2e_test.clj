(ns kontor.payroll-au.e2e-test
  "Stage R C6 — end-to-end Xero GL CSV → kontor `run-payroll!` test
   (ADR-080).

   The headline scenario: an Australian Pty Ltd with three remote
   employees in NSW / VIC / QLD, monthly payroll. The Xero GL CSV
   carries all three employees' pay-period rows; kontor:

     1. Parses the CSV (XeroGlComputeProvider).
     2. Validates `check-facts` per employee.
     3. Builds a balanced transaction (single book ledger here; the
        parallel-ledger split is not in scope for AU since the
        Australian Accounting Standards Board doesn't require a
        book/tax-basis ledger split equivalent to the US ASC 710
        case — fringe-benefit tax + state-payroll-tax are separate
        cycles, not parallel ledgers).
     4. Attaches a `:state` analytic distribution on every wage-side
        posting (mirrors the US ADP pattern per ADR-077).
     5. Emits a STP Phase 2 pay-event audit-doc.
     6. Records the `:payroll-run` row with control totals.

   Exercises the FULL kernel gate stack via
   `kontor.hr.payroll/run-payroll!`."
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
            [kontor.l10n-au.chart :as au-chart]
            [kontor.payroll-au.chart :as pau-chart]
            [kontor.payroll-au.core :as au]
            [kontor.payroll-au.emit :as au-emit]
            [kontor.payroll-au.super :as au-super])
  (:import [java.math BigDecimal]))

(defn- ref-eid [db a v]
  (d/q '[:find ?e . :in $ ?a ?v :where [?e ?a ?v]] db a v))

(defn- bootstrap
  []
  (let [conn (core/create-test-db)]
    (hr/install! conn)
    (au-chart/install! conn)         ; base AU chart (AUD commodity)
    (pau-chart/install! conn)        ; payroll-extension chart + tags
    (au/install! conn)               ; :state analytic plan
    (d/transact conn
                [{:db/id "ent-acme" :kontor.entity/code "ACME-AU"
                  :kontor.entity/name "Acme Australia Pty Ltd"
                  :kontor.entity/kind :operating}
                 {:db/id "journal-pay"
                  :journal/code "PAY-AU"
                  :journal/name "Payroll (AU)"
                  :journal/type :general}
                 {:db/id "period-2026-05"
                  :period/name "2026-05"
                  :period/start #inst "2026-05-01"
                  :period/end #inst "2026-06-01"}])
    conn))

(defn- setup-employees [conn]
  (let [db (d/db conn)
        ent (ref-eid db :kontor.entity/code "ACME-AU")]
    (doseq [[ext given family] [["P-E101" "Alice" "Outback"]
                                ["P-E102" "Bob"   "Bondi"]
                                ["P-E103" "Carol" "Cairns"]]]
      (person/create-person! conn {:external-id ext
                                   :given-name given
                                   :family-name family}))
    (let [db (d/db conn)
          alice (hr/person-by-external-id db "P-E101")
          bob (hr/person-by-external-id db "P-E102")
          carol (hr/person-by-external-id db "P-E103")]
      (employment/hire! conn {:code "E101" :person alice :entity ent
                              :start-date #inst "2025-01-01"
                              :job-title "Senior Engineer (NSW)"})
      (employment/hire! conn {:code "E102" :person bob :entity ent
                              :start-date #inst "2025-01-01"
                              :job-title "Senior Engineer (VIC)"})
      (employment/hire! conn {:code "E103" :person carol :entity ent
                              :start-date #inst "2025-01-01"
                              :job-title "Senior Engineer (QLD)"}))))

(defn- accounts-map [db]
  {:au-payroll-wages              (ref-eid db :account/code "477")
   :au-payroll-er-super           (ref-eid db :account/code "478")
   :au-payroll-er-state-tax       (ref-eid db :account/code "479")
   :au-payroll-er-workers-comp    (ref-eid db :account/code "480")
   :au-payroll-net-wages          (ref-eid db :account/code "814")
   :au-payroll-paygw              (ref-eid db :account/code "825")
   :au-payroll-super              (ref-eid db :account/code "826")
   :au-payroll-super-employee     (ref-eid db :account/code "827")
   :au-payroll-salary-sacrifice   (ref-eid db :account/code "828")
   :au-payroll-state-tax          (ref-eid db :account/code "829")
   :au-payroll-workers-comp       (ref-eid db :account/code "830")
   :au-payroll-child-support      (ref-eid db :account/code "831")
   :au-payroll-other-deduction    (ref-eid db :account/code "832")})

(def pay-element-codes
  {"OTE"              :ordinary-time-earnings
   "OVT"              :overtime
   "BONUS"            :bonus
   "PAYGW"            :paygw
   "SS-SUPER"         :salary-sacrifice-super
   "NET"              :__skip-payable
   "SUPER-ER-SG"      {:kind :superannuation-guarantee-employer
                       :employer-side? true}
   "SUPER-ER-PAY"     :__skip-payable})

(deftest au-pty-three-employees-three-states-end-to-end
  (let [conn (bootstrap)
        _ (setup-employees conn)
        db (d/db conn)
        ent (ref-eid db :kontor.entity/code "ACME-AU")
        aud (ref-eid db :kontor.commodity/symbol "AUD")
        journal (ref-eid db :journal/code "PAY-AU")
        period (ref-eid db :period/name "2026-05")
        e101 (hr/employment-by-code db "E101")
        e102 (hr/employment-by-code db "E102")
        e103 (hr/employment-by-code db "E103")
        _ (doseq [emp [e101 e102 e103]]
            (comp/set-compensation!
             conn {:employment emp
                   :effective-from #inst "2025-01-01"
                   :commodity aud
                   :components [{:kind :base-wage :amount 78000M :period :annual}]}))
        _ (pp/create-pay-period!
           conn {:code "ACME-2026-05" :entity ent
                 :start-date #inst "2026-05-01"
                 :end-date #inst "2026-05-31"
                 :frequency :monthly
                 :fiscal-period period})
        pp-eid (hr/pay-period-by-code (d/db conn) "ACME-2026-05")
        db (d/db conn)
        accounts (accounts-map db)
        ext->eid {"E101" e101 "E102" e102 "E103" e103}
        compute-provider (au/make-xero-gl-compute-provider
                          {:csv-source (io/resource
                                        "kontor/payroll_au/fixtures/xero_3_employees_3_states.csv")
                           :pay-element-codes pay-element-codes
                           :external-id->eid ext->eid
                           :commodity-eid aud})
        posting-builder (au/make-au-payroll-posting-builder {:commodity aud})
        payees-info
        {e101 {:tfn "123456782" :given-name "Alice" :family-name "Outback"
               :employee-id "E101" :tax-treatment-code "RTRRRR"
               :ytd {:gross 78000M :ote 78000M :paygw 14400M :super 8970M}}
         e102 {:tfn "123456782" :given-name "Bob" :family-name "Bondi"
               :employee-id "E102" :tax-treatment-code "RTRRRR"
               :ytd {:gross 90000M :ote 90000M :paygw 19200M :super 10350M}}
         e103 {:tfn "123456782" :given-name "Carol" :family-name "Cairns"
               :employee-id "E103" :tax-treatment-code "RTRRRR"
               :ytd {:gross 75600M :ote 75600M :paygw 13200M :super 8004M}}}
        emit-provider (au/make-au-stp-emit-provider
                       {:abn "33051775556"
                        :pay-period-start #inst "2026-05-01"
                        :pay-period-end #inst "2026-05-31"
                        :pay-date #inst "2026-05-31"
                        :payees-info payees-info
                        :submission-id "SUB-2026-05-001"
                        :bms-id "KONTOR-AU-1.0"})
        report (payroll/run-payroll!
                conn {:pay-period pp-eid
                      :entity ent
                      :employments [e101 e102 e103]
                      :compute-provider compute-provider
                      :posting-builder posting-builder
                      :emit-provider emit-provider
                      :accounts accounts
                      :run-code "ACME-2026-05-001"
                      :tx-code "TX-ACME-2026-05"
                      :journal journal
                      :commodity aud})
        db' (:db-after report)
        run-eid (d/q '[:find ?r . :in $ ?c
                       :where [?r :payroll-run/code ?c]]
                     db' "ACME-2026-05-001")
        run (d/pull db' '[* {:payroll-run/payroll-transaction
                             [:transaction/external-id
                              {:posting/_transaction
                               [:posting/amount
                                {:posting/account [:account/code]}
                                {:posting/analytic-distributions
                                 [:analytic-distribution/percent
                                  {:analytic-distribution/account
                                   [:analytic-account/code]}]}]}]}]
                    run-eid)
        postings (-> run :payroll-run/payroll-transaction :posting/_transaction)]
    (testing "the payroll-run row is created"
      (is (some? run-eid))
      (is (= :xero-gl (:payroll-run/provider-id run))))
    (testing "control totals reflect all three employees"
      ;; Gross: 6500 + (7200+300) + (5800+500) = 6500 + 7500 + 6300 = 20,300
      (is (= 20300.00M (:payroll-run/control-total-gross run)))
      ;; Net: 4650 + 5900 + 5200 = 15,750
      (is (= 15750.00M (:payroll-run/control-total-net run))))
    (testing "the linked transaction balances per-(ledger, commodity)"
      (let [sum (reduce (fn [^BigDecimal a {:keys [posting/amount]}]
                          (.add a ^BigDecimal amount))
                        0M postings)]
        (is (zero? (.signum sum)))))
    (testing "every wage-side posting carries a :state analytic distribution"
      (let [with-dist (filter #(seq (:posting/analytic-distributions %)) postings)]
        (is (seq with-dist))
        (let [state-codes (->> with-dist
                               (mapcat :posting/analytic-distributions)
                               (map :analytic-distribution/account)
                               (map :analytic-account/code)
                               distinct
                               set)]
          (is (= #{"NSW" "VIC" "QLD"} state-codes)))))
    (testing "STP Phase 2 emit-doc was produced"
      (let [docs (d/q '[:find [?e ...]
                        :where [?e :audit-doc/category :payroll-filing]
                        [?e :audit-doc/type :stp-pay-event]]
                      db')]
        (is (>= (count docs) 1))))
    (testing "the emit-doc is linked from :payroll-run/emit-docs (P0-86-1 fix)"
      (is (seq (:payroll-run/emit-docs run))))))

;; ============================================================================
;; SuperStream remittance helper composed standalone (typically monthly,
;; separate cadence from the per-pay-period run).
;; ============================================================================

(deftest superstream-message-composes-end-to-end
  (let [line (au-super/contribution-line
              {:member {:given-name "Alice" :family-name "Outback"
                        :tfn "123456782" :member-number "M-1001"
                        :date-of-birth #inst "1985-03-12"}
               :fund {:usi "HST0100AU" :abn "64971749321"
                      :name "HESTA Super Fund"}
               :sg-amount 747.50M
               :pay-period-start #inst "2026-05-01"
               :pay-period-end #inst "2026-05-31"})
        payload (au-super/contribution-message-payload
                 {:abn "33051775556"
                  :usi "ATO0001AU"
                  :pay-period-start #inst "2026-05-01"
                  :pay-period-end #inst "2026-05-31"
                  :submission-date #inst "2026-06-15"
                  :lines [line]
                  :total-amount 747.50M
                  :clearing-house-name "Small Business Super Clearing House"})
        tx (au-super/superstream-audit-doc-tx-data {:payload payload})
        doc (first tx)]
    (testing "the SuperStream audit-doc is :payroll-filing"
      (is (= :payroll-filing (:audit-doc/category doc)))
      (is (= :en             (:audit-doc/language doc)))
      (is (= :superstream-contribution (:audit-doc/type doc))))
    (testing "payload total matches the line total"
      (is (= "747.50" (:super.message/total-amount payload))))))

;; ============================================================================
;; Termination event helper
;; ============================================================================

(deftest terminate-employment-tx-data-emits-audit-doc
  (let [conn (bootstrap)
        _ (setup-employees conn)
        db (d/db conn)
        e101 (hr/employment-by-code db "E101")
        tx (au-emit/terminate-employment-tx-data
            db {:employment-eid e101
                :last-day-worked #inst "2026-05-31"
                :termination-reason :redundancy
                :final-pay-period-end-date #inst "2026-06-15"
                :separation-payments {:unused-leave 4200M}})
        [audit-doc emp-update] tx]
    (testing "audit-doc carries the right category + cessation-code hint"
      (is (= :hr-personnel (:audit-doc/category audit-doc)))
      (is (= :termination-event (:audit-doc/type audit-doc)))
      (is (re-find #"CessationTypeCode: R" (:audit-doc/description audit-doc))))
    (testing "the employment update transitions to :terminated"
      (is (= :terminated (:employment/state emp-update)))
      (is (= e101 (:db/id emp-update)))
      (is (= #inst "2026-05-31" (:employment/end-date emp-update)))
      (is (= :redundancy (:employment/termination-reason emp-update)))
      (is (= #inst "2026-06-15"
             (:employment/final-pay-period-end-date emp-update))))))

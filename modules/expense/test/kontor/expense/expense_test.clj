(ns kontor.expense.expense-test
  "ADR-061: kontor-expense — employee expense reports.

   Covers:
   - create-report! → :draft, stamps :kontor.audit/create-uid = employee.
   - add-line! bumps the cached total; rejects a non-:draft report.
   - submit! → :submitted; the inline receipt guard.
   - approve! → :approved; :no-self-approval rejects the employee.
   - reject! requires :reason-note.
   - post-report! builds the GL (Dr expense / Cr payable for
     own-account, Cr card-clearing for company-account), → :posted.
   - reimburse! settles the own-account portion, → :reimbursed."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [kontor.core :as core]
            [kontor.expense.core :as expense]
            [kontor.expense.schema :as expense-schema]))

;; ============================================================================
;; Fixture
;; ============================================================================

(defn- bootstrap []
  (let [conn (core/create-test-db)]
    (expense-schema/install! conn)
    (d/transact conn
                [{:db/id "eur" :kontor.commodity/symbol "EUR" :kontor.commodity/precision 2}
                 ;; Employee + approver (:partner stand-ins for :kontor.audit/create-uid).
                 {:kontor.partner/external-id "E-alice" :kontor.partner/name "Alice (employee)"}
                 {:kontor.partner/external-id "M-bob"   :kontor.partner/name "Bob (manager)"}
                 ;; Expense categories — generic refs; :partner stand-ins.
                 {:kontor.partner/external-id "CAT-travel" :kontor.partner/name "Travel"}
                 {:kontor.partner/external-id "CAT-meals"  :kontor.partner/name "Meals"}
                 ;; GL accounts.
                 {:db/id "acct-travel" :kontor.account/code "6700" :kontor.account/name "Travel Expense"
                  :kontor.account/type :expense :kontor.account/active true}
                 {:db/id "acct-meals" :kontor.account/code "6710" :kontor.account/name "Meals Expense"
                  :kontor.account/type :expense :kontor.account/active true}
                 {:db/id "acct-payable" :kontor.account/code "1740"
                  :kontor.account/name "Employee Reimbursement Payable"
                  :kontor.account/type :liability :kontor.account/active true}
                 {:db/id "acct-card" :kontor.account/code "1745"
                  :kontor.account/name "Corporate Card Clearing"
                  :kontor.account/type :liability :kontor.account/active true}
                 {:db/id "acct-cash" :kontor.account/code "1800" :kontor.account/name "Bank"
                  :kontor.account/type :asset :kontor.account/active true}
                 {:db/id "journal-gen" :kontor.journal/code "GEN" :kontor.journal/name "General"
                  :kontor.journal/type :general}
                 ;; Receipts.
                 {:db/id "doc-r1" :audit-doc/code "RCPT-1"
                  :audit-doc/type :receipt :audit-doc/storage-uri "s3://r/1"
                  :audit-doc/uploaded-at #inst "2026-04-02"}
                 {:db/id "doc-r2" :audit-doc/code "RCPT-2"
                  :audit-doc/type :receipt :audit-doc/storage-uri "s3://r/2"
                  :audit-doc/uploaded-at #inst "2026-04-03"}])
    conn))

(defn- ref-eid [db a v]
  (d/q '[:find ?e . :in $ ?a ?v :where [?e ?a ?v]] db a v))

(defn- p       [db code] (ref-eid db :kontor.partner/external-id code))
(defn- acct    [db code] (ref-eid db :kontor.account/code code))
(defn- doc     [db code] (ref-eid db :audit-doc/code code))
(defn- eur     [db] (ref-eid db :kontor.commodity/symbol "EUR"))
(defn- journal [db] (ref-eid db :kontor.journal/code "GEN"))

;; An EXP-1 report with two own-account lines (travel 200 + meals 50).
(defn- two-line-report! [conn]
  (let [db (d/db conn)]
    (expense/create-report! conn {:code "EXP-1" :employee (p db "E-alice")
                                  :report-date #inst "2026-04-05"
                                  :commodity (eur db)})
    (expense/add-line! conn {:expense-report "EXP-1"
                             :category (p (d/db conn) "CAT-travel")
                             :expense-date #inst "2026-04-02" :amount 200.00M
                             :commodity (eur (d/db conn)) :payment-mode :own-account
                             :expense-account (acct (d/db conn) "6700")
                             :supporting-doc (doc (d/db conn) "RCPT-1")})
    (expense/add-line! conn {:expense-report "EXP-1"
                             :category (p (d/db conn) "CAT-meals")
                             :expense-date #inst "2026-04-03" :amount 50.00M
                             :commodity (eur (d/db conn)) :payment-mode :own-account
                             :expense-account (acct (d/db conn) "6710")
                             :supporting-doc (doc (d/db conn) "RCPT-2")})
    (expense/by-code (d/db conn) "EXP-1")))

;; ============================================================================
;; create-report! / add-line!
;; ============================================================================

(deftest create-report-and-add-lines
  (let [conn (bootstrap)
        _ (two-line-report! conn)
        r (expense/pull-report (d/db conn) "EXP-1")]
    (testing "the report is :draft, owned by the employee"
      (is (= :draft (:expense-report/status r)))
      (is (= "E-alice" (:kontor.partner/external-id (:expense-report/employee r)))))
    (testing ":kontor.audit/create-uid is stamped to the employee (for :no-self-approval)"
      (is (= (p (d/db conn) "E-alice")
             (:db/id (:kontor.audit/create-uid (d/pull (d/db conn) [:kontor.audit/create-uid]
                                          (expense/by-code (d/db conn) "EXP-1")))))))
    (testing "the cached total tracks the lines"
      (is (= 250.00M (:expense-report/total r)))
      (is (= 250.00M (expense/report-total (d/db conn) "EXP-1")))
      (is (= 2 (count (expense/lines-of (d/db conn) "EXP-1")))))))

(deftest add-line-rejects-non-draft-report
  (let [conn (bootstrap)
        _ (two-line-report! conn)
        _ (expense/submit! conn {:expense-report "EXP-1"
                                 :changed-by-uid (p (d/db conn) "E-alice")})]
    (testing "a submitted report refuses new lines"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"only add lines to a :draft"
           (expense/add-line! conn {:expense-report "EXP-1"
                                    :category (p (d/db conn) "CAT-meals")
                                    :expense-date #inst "2026-04-04" :amount 10M
                                    :commodity (eur (d/db conn))
                                    :payment-mode :own-account
                                    :expense-account (acct (d/db conn) "6710")}))))))

;; ============================================================================
;; submit! / approve! / reject!
;; ============================================================================

(deftest submit-requires-receipts
  (let [conn (bootstrap)
        db (d/db conn)
        _ (expense/create-report! conn {:code "EXP-NR" :employee (p db "E-alice")
                                        :report-date #inst "2026-04-05"
                                        :commodity (eur db)})
        ;; A line with NO supporting-doc.
        _ (expense/add-line! conn {:expense-report "EXP-NR"
                                   :category (p (d/db conn) "CAT-travel")
                                   :expense-date #inst "2026-04-02" :amount 99M
                                   :commodity (eur (d/db conn))
                                   :payment-mode :own-account
                                   :expense-account (acct (d/db conn) "6700")})]
    (testing "submit! refuses a line with no receipt by default"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"needs a :supporting-doc"
           (expense/submit! conn {:expense-report "EXP-NR"
                                  :changed-by-uid (p (d/db conn) "E-alice")}))))
    (testing ":require-receipts? false overrides the guard (e.g. mileage)"
      (expense/submit! conn {:expense-report "EXP-NR"
                             :changed-by-uid (p (d/db conn) "E-alice")
                             :require-receipts? false})
      (is (= :submitted (:expense-report/status
                         (expense/pull-report (d/db conn) "EXP-NR")))))))

(deftest approve-enforces-no-self-approval
  (let [conn (bootstrap)
        _ (two-line-report! conn)
        _ (expense/submit! conn {:expense-report "EXP-1"
                                 :changed-by-uid (p (d/db conn) "E-alice")})]
    (testing "the employee cannot approve their own report"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"(?i)approval-policy"
           (expense/approve! conn {:expense-report "EXP-1"
                                   :changed-by-uid (p (d/db conn) "E-alice")}))))
    (testing "a different approver can"
      (expense/approve! conn {:expense-report "EXP-1"
                              :changed-by-uid (p (d/db conn) "M-bob")})
      (is (= :approved (:expense-report/status
                        (expense/pull-report (d/db conn) "EXP-1")))))))

(deftest reject-requires-reason-note
  (let [conn (bootstrap)
        _ (two-line-report! conn)
        _ (expense/submit! conn {:expense-report "EXP-1"
                                 :changed-by-uid (p (d/db conn) "E-alice")})]
    (testing "reject! without :reason-note is refused"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #":reason-note required"
           (expense/reject! conn {:expense-report "EXP-1"
                                  :changed-by-uid (p (d/db conn) "M-bob")}))))
    (testing "reject! with a reason note succeeds"
      (expense/reject! conn {:expense-report "EXP-1"
                             :changed-by-uid (p (d/db conn) "M-bob")
                             :reason-note "Missing itemised hotel receipt."})
      (is (= :rejected (:expense-report/status
                        (expense/pull-report (d/db conn) "EXP-1")))))))

;; ============================================================================
;; Lifecycle integrity — out-of-order calls must be refused (review-after P0)
;; ============================================================================

(deftest lifecycle-rejects-out-of-order-transitions
  (let [conn (bootstrap)
        _ (two-line-report! conn)]
    (testing "approve! on a :draft report is refused — the submit gate is real"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"only approve a :submitted"
           (expense/approve! conn {:expense-report "EXP-1"
                                   :changed-by-uid (p (d/db conn) "M-bob")}))))
    (testing "submit! on an already-:submitted report is refused"
      (expense/submit! conn {:expense-report "EXP-1"
                             :changed-by-uid (p (d/db conn) "E-alice")})
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"only submit a :draft"
           (expense/submit! conn {:expense-report "EXP-1"
                                  :changed-by-uid (p (d/db conn) "E-alice")}))))))

(deftest reopen-a-rejected-report-and-resubmit
  (let [conn (bootstrap)
        _ (two-line-report! conn)
        _ (expense/submit! conn {:expense-report "EXP-1"
                                 :changed-by-uid (p (d/db conn) "E-alice")})
        _ (expense/reject! conn {:expense-report "EXP-1"
                                 :changed-by-uid (p (d/db conn) "M-bob")
                                 :reason-note "Wrong cost center."})]
    (testing "reopen! brings a :rejected report back to :draft"
      (expense/reopen! conn {:expense-report "EXP-1"
                             :changed-by-uid (p (d/db conn) "E-alice")})
      (is (= :draft (:expense-report/status
                     (expense/pull-report (d/db conn) "EXP-1")))))
    (testing "the corrected report can be resubmitted"
      (expense/submit! conn {:expense-report "EXP-1"
                             :changed-by-uid (p (d/db conn) "E-alice")})
      (is (= :submitted (:expense-report/status
                         (expense/pull-report (d/db conn) "EXP-1")))))
    (testing "reopen! only applies to a :rejected report"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"only reopen a :rejected"
           (expense/reopen! conn {:expense-report "EXP-1"
                                  :changed-by-uid (p (d/db conn) "E-alice")}))))))

;; ============================================================================
;; post-report! / reimburse!
;; ============================================================================

(defn- posting-amounts [db tx-eid]
  (->> (d/q '[:find [?p ...] :in $ ?t :where [?p :kontor.posting/transaction ?t]] db tx-eid)
       (map #(d/pull db [:kontor.posting/amount {:kontor.posting/account [:kontor.account/code]}] %))
       (map (juxt #(:kontor.account/code (:kontor.posting/account %)) :kontor.posting/amount))
       set))

(deftest post-report-builds-the-gl-and-reimburse-settles
  (let [conn (bootstrap)
        _ (two-line-report! conn)
        _ (expense/submit! conn {:expense-report "EXP-1"
                                 :changed-by-uid (p (d/db conn) "E-alice")})
        _ (expense/approve! conn {:expense-report "EXP-1"
                                  :changed-by-uid (p (d/db conn) "M-bob")})
        _ (expense/post-report! conn {:expense-report "EXP-1"
                                      :journal (journal (d/db conn))
                                      :reimbursement-payable-account
                                      (acct (d/db conn) "1740")
                                      :changed-by-uid (p (d/db conn) "M-bob")})
        r (d/pull (d/db conn) [:expense-report/status
                               {:expense-report/transaction [:db/id]}]
                  (expense/by-code (d/db conn) "EXP-1"))]
    (testing "post-report! → :posted, GL linked"
      (is (= :posted (:expense-report/status r)))
      (is (some? (:db/id (:expense-report/transaction r)))))
    (testing "the GL entry: Dr travel 200 + Dr meals 50 / Cr payable 250"
      (is (= #{["6700" 200.00M] ["6710" 50.00M] ["1740" -250.00M]}
             (posting-amounts (d/db conn)
                              (:db/id (:expense-report/transaction r))))))
    (testing "the posting is sealed"
      (is (every? #(some? (:kontor.posting/posted-at %))
                  (->> (d/q '[:find [?p ...] :in $ ?t
                              :where [?p :kontor.posting/transaction ?t]]
                            (d/db conn) (:db/id (:expense-report/transaction r)))
                       (map #(d/pull (d/db conn) [:kontor.posting/posted-at] %))))))
    (testing "reimburse! settles the own-account total → :reimbursed"
      (expense/reimburse! conn {:expense-report "EXP-1"
                                :journal (journal (d/db conn))
                                :cash-account (acct (d/db conn) "1800")
                                :reimbursement-payable-account (acct (d/db conn) "1740")})
      (let [r' (d/pull (d/db conn)
                       [:expense-report/status
                        {:expense-report/reimbursement-transaction [:db/id]}]
                       (expense/by-code (d/db conn) "EXP-1"))]
        (is (= :reimbursed (:expense-report/status r')))
        (is (= #{["1740" 250.00M] ["1800" -250.00M]}
               (posting-amounts (d/db conn)
                                (:db/id (:expense-report/reimbursement-transaction r')))))))))

(deftest post-report-company-account-credits-card-clearing
  (let [conn (bootstrap)
        db (d/db conn)
        _ (expense/create-report! conn {:code "EXP-CC" :employee (p db "E-alice")
                                        :report-date #inst "2026-04-05"
                                        :commodity (eur db)})
        _ (expense/add-line! conn {:expense-report "EXP-CC"
                                   :category (p (d/db conn) "CAT-travel")
                                   :expense-date #inst "2026-04-02" :amount 120.00M
                                   :commodity (eur (d/db conn))
                                   :payment-mode :company-account
                                   :expense-account (acct (d/db conn) "6700")
                                   :supporting-doc (doc (d/db conn) "RCPT-1")})
        _ (expense/submit! conn {:expense-report "EXP-CC"
                                 :changed-by-uid (p (d/db conn) "E-alice")})
        _ (expense/approve! conn {:expense-report "EXP-CC"
                                  :changed-by-uid (p (d/db conn) "M-bob")})
        _ (expense/post-report! conn {:expense-report "EXP-CC"
                                      :journal (journal (d/db conn))
                                      :card-clearing-account (acct (d/db conn) "1745")})
        tx (:db/id (:expense-report/transaction
                    (d/pull (d/db conn) [{:expense-report/transaction [:db/id]}]
                            (expense/by-code (d/db conn) "EXP-CC"))))]
    (testing "a company-account line credits the corporate-card-clearing account"
      (is (= #{["6700" 120.00M] ["1745" -120.00M]}
             (posting-amounts (d/db conn) tx))))
    (testing "a company-account-only report cannot be reimbursed"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"no :own-account lines"
           (expense/reimburse! conn {:expense-report "EXP-CC"
                                     :journal (journal (d/db conn))
                                     :cash-account (acct (d/db conn) "1800")
                                     :reimbursement-payable-account
                                     (acct (d/db conn) "1740")}))))))

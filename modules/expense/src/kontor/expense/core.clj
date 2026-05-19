(ns kontor.expense.core
  "kontor-expense — employee expense reports (ADR-061).

   An expense report is a small approval-gated document that
   composes a GL entry. The lifecycle (draft → submitted → approved
   → posted → reimbursed) rides the ADR-034 status machine; the
   `:submitted → :approved` edge is `:no-self-approval`-gated
   (ADR-038 — an employee cannot approve their own report).

   `post-report!` builds the GL entry via
   `kontor.posting/build-transaction`: each line debits its
   `:expense-account`; the credit leg is grouped by
   `:expense-line/payment-mode` — `:own-account` credits an
   employee-reimbursement-payable (later settled by `reimburse!`),
   `:company-account` credits a corporate-card-clearing account
   (closed by bank-statement matching)."
  (:require [datahike.api :as d]
            [kontor.bitemporal :as kbt]
            [kontor.posting :as posting]
            [kontor.status-machine :as sm]
            [kontor.validation :as validation])
  (:import [java.math BigDecimal]
           [java.util Date]))

;; ============================================================================
;; Resolution / queries
;; ============================================================================

(defn by-code
  "Resolve an :expense-report eid by :expense-report/code."
  [db code]
  (d/q '[:find ?e . :in $ ?c :where [?e :expense-report/code ?c]] db code))

(defn resolve-report
  "Coerce `spec` to an :expense-report eid (string → by-code)."
  [db spec]
  (cond
    (nil? spec)    nil
    (string? spec) (by-code db spec)
    :else          spec))

(defn lines-of
  "All :expense-line entities for a report, ordered by :expense-date."
  [db report-spec]
  (when-let [eid (resolve-report db report-spec)]
    (->> (d/q '[:find [?l ...]
                :in $ ?r
                :where [?l :expense-line/expense-report ?r]]
              db eid)
         (map #(d/pull db '[*] %))
         (sort-by :expense-line/expense-date)
         vec)))

(defn report-total
  "`Σ :expense-line/amount` for a report — the truth behind the
   cached `:expense-report/total`."
  ^BigDecimal [db report-spec]
  (let [eid (resolve-report db report-spec)]
    (or (when eid
          (d/q '[:find (sum ?amt) .
                 :with ?l
                 :in $ ?r
                 :where
                 [?l :expense-line/expense-report ?r]
                 [?l :expense-line/amount ?amt]]
               db eid))
        0M)))

(defn pull-report
  "Pull an :expense-report (by code or eid) with its employee +
   status."
  [db report-spec]
  (when-let [eid (resolve-report db report-spec)]
    (d/pull db '[* {:expense-report/employee [:partner/external-id
                                              :partner/name]}]
            eid)))

;; ============================================================================
;; create-report! / add-line!
;; ============================================================================

(declare create-report-tx-data add-line-tx-data post-report-tx-data
         reimburse-tx-data)

(defn create-report-tx-data
  "Pure tx-data builder for `create-report!` (ADR-068)."
  [db {:keys [code employee report-date commodity note tempid changed-at]
       :or {tempid "expense-report-1"}}]
  (when-not code        (throw (ex-info ":code required" {})))
  (when-not employee    (throw (ex-info ":employee required" {})))
  (when-not report-date (throw (ex-info ":report-date required" {})))
  (when-not commodity   (throw (ex-info ":commodity required" {})))
  (let [row (cond-> {:db/id tempid
                     :expense-report/code code
                     :expense-report/employee employee
                     :expense-report/status :draft
                     :expense-report/report-date report-date
                     :expense-report/commodity commodity
                     :expense-report/total 0M
                     ;; The employee IS the creator — :no-self-approval
                     ;; compares the approver's :changed-by-uid to this.
                     :create/uid employee}
              note (assoc :expense-report/note note))
        status-tx (sm/record-status-change-tx-data
                   db {:entity tempid
                       :entity-type :expense-report
                       :facet :expense-report/status
                       :from :nil :to :draft
                       :changed-at (or changed-at (Date.))
                       :changed-by-uid employee
                       :reason :expense-report-created})]
    (into [row] status-tx)))

(defn create-report!
  "Create an :expense-report in `:draft`. The employee is stamped as
   `:create/uid` so the ADR-038 :no-self-approval rule fires on
   approval. Routes through the gate (ADR-068). Returns the tx-report.

   Required opts: :code, :employee (ref/eid of :partner),
                  :report-date, :commodity.
   Optional: :note, :vt-from / :vt-to (valid-time bounds, default
             :vt-from = :report-date).

   The pure tx-data builder is `create-report-tx-data`."
  [conn {:keys [report-date vt-from vt-to] :as opts}]
  (let [now (Date.)]
    (validation/transact-with-validation
     conn (kbt/with-vt (create-report-tx-data
                        (d/db conn) (assoc opts :changed-at now))
                       (or vt-from report-date)
                       (or vt-to kbt/forever)))))

(defn add-line-tx-data
  "Pure tx-data builder for `add-line!` (ADR-068)."
  [db {:keys [expense-report category expense-date amount commodity
              payment-mode expense-account cost-center supporting-doc
              description]}]
  (when-not category        (throw (ex-info ":category required" {})))
  (when-not expense-date    (throw (ex-info ":expense-date required" {})))
  (when (nil? amount)       (throw (ex-info ":amount required" {})))
  (when-not commodity       (throw (ex-info ":commodity required" {})))
  (when-not (#{:own-account :company-account} payment-mode)
    (throw (ex-info ":payment-mode must be :own-account or :company-account"
                    {:payment-mode payment-mode})))
  (when-not expense-account (throw (ex-info ":expense-account required" {})))
  (let [report (resolve-report db expense-report)
        _ (when-not report (throw (ex-info "Expense report not found"
                                           {:spec expense-report})))
        status (:expense-report/status
                (d/pull db [:expense-report/status] report))
        _ (when-not (= :draft status)
            (throw (ex-info "Can only add lines to a :draft report"
                            {:type :expense/report-not-draft :status status})))
        line (cond-> {:expense-line/expense-report report
                      :expense-line/category category
                      :expense-line/expense-date expense-date
                      :expense-line/amount amount
                      :expense-line/commodity commodity
                      :expense-line/payment-mode payment-mode
                      :expense-line/expense-account expense-account}
               cost-center    (assoc :expense-line/cost-center cost-center)
               supporting-doc (assoc :expense-line/supporting-doc supporting-doc)
               description    (assoc :expense-line/description description))
        new-total (.add (report-total db report) ^BigDecimal amount)]
    [line
     {:db/id report :expense-report/total new-total}]))

(defn add-line!
  "Add an :expense-line to a `:draft` report and bump the cached
   `:expense-report/total`. Routes through the gate (ADR-068).
   Returns the tx-report.

   Required opts: :expense-report (code/eid), :category (ref),
                  :expense-date, :amount, :commodity, :payment-mode
                  (#{:own-account :company-account}), :expense-account.
   Optional: :cost-center, :supporting-doc, :description.

   The pure tx-data builder is `add-line-tx-data`."
  [conn opts]
  (validation/transact-with-validation
   conn (add-line-tx-data (d/db conn) opts)))

;; ============================================================================
;; Lifecycle — submit! / approve! / reject!
;; ============================================================================

(defn change-status-tx-data
  "Pure tx-data builder for `change-status!` (ADR-068). Public so a
   consumer composing this expense-report transition into a larger
   `kontor.process` step list — alongside, say, a payment-application
   write or an audit-doc attachment — gets the same status-machine +
   gate guarantees as a standalone `change-status!` call."
  [db report-eid from to {:keys [changed-by-uid reason reason-note
                                  supporting-doc changed-at]}]
  (sm/record-status-change-tx-data
   db (cond-> {:entity report-eid
               :entity-type :expense-report
               :facet :expense-report/status
               :from from :to to
               :changed-at (or changed-at (Date.))}
        changed-by-uid (assoc :changed-by-uid changed-by-uid)
        reason         (assoc :reason reason)
        reason-note    (assoc :reason-note reason-note)
        supporting-doc (assoc :supporting-doc supporting-doc))))

(defn change-status!
  "Drive an :expense-report/status transition through the status
   machine, wrapped in valid-time. Routes through the gate (ADR-068).

   Public so consumers can drive transitions the convenience wrappers
   (`submit!`, `approve!`, `reject!`, `unreject!`) don't cover. The
   pure tx-data builder is `change-status-tx-data` (per ADR-068)."
  [conn report-eid from to {:keys [vt-from vt-to] :as opts}]
  (let [now (Date.)]
    (validation/transact-with-validation
     conn (kbt/with-vt (change-status-tx-data
                        (d/db conn) report-eid from to
                        (assoc opts :changed-at now))
                       (or vt-from now)
                       (or vt-to kbt/forever)))))

(defn submit!
  "Submit a `:draft` report for approval (`:draft → :submitted`).
   Inline guard: unless `:require-receipts?` is false, every
   `:expense-line` must carry a `:supporting-doc` (the receipt).

   Required: :expense-report, :changed-by-uid.
   Optional: :require-receipts? (default true), :reason-note,
             :vt-from, :vt-to."
  [conn {:keys [expense-report changed-by-uid require-receipts?]
         :or {require-receipts? true}
         :as opts}]
  (when-not changed-by-uid (throw (ex-info ":changed-by-uid required" {})))
  (let [db (d/db conn)
        report (resolve-report db expense-report)
        _ (when-not report (throw (ex-info "Expense report not found"
                                           {:spec expense-report})))
        ;; Read the REAL current status — never trust a hard-coded
        ;; `:from`, or the whole approval gate is bypassable by
        ;; calling the transactors out of order (review-after P0).
        status (:expense-report/status
                (d/pull db [:expense-report/status] report))
        _ (when-not (= :draft status)
            (throw (ex-info "Can only submit a :draft report"
                            {:type :expense/report-not-draft :status status})))
        lines (lines-of db report)
        _ (when (empty? lines)
            (throw (ex-info "Cannot submit a report with no lines"
                            {:type :expense/no-lines :report report})))
        _ (when (and require-receipts?
                     (some #(nil? (:expense-line/supporting-doc %)) lines))
            (throw (ex-info "Every expense line needs a :supporting-doc (receipt) to submit — pass :require-receipts? false to override"
                            {:type :expense/missing-receipt :report report})))]
    (change-status! conn report status :submitted
                    (assoc opts :reason :expense-report-submitted))))

(defn approve!
  "Approve a `:submitted` report (`:submitted → :approved`). The
   ADR-038 `:no-self-approval` policy fires — `:changed-by-uid` must
   differ from the employee (`create-report!` stamped the employee
   as `:create/uid`).

   Required: :expense-report, :changed-by-uid.
   Optional: :reason-note, :supporting-doc, :vt-from, :vt-to."
  [conn {:keys [expense-report changed-by-uid] :as opts}]
  (when-not changed-by-uid (throw (ex-info ":changed-by-uid required" {})))
  (let [db (d/db conn)
        report (resolve-report db expense-report)
        _ (when-not report (throw (ex-info "Expense report not found"
                                           {:spec expense-report})))
        ;; Read the REAL status (review-after P0 — no hard-coded :from).
        status (:expense-report/status
                (d/pull db [:expense-report/status] report))
        _ (when-not (= :submitted status)
            (throw (ex-info "Can only approve a :submitted report"
                            {:type :expense/report-not-submitted :status status})))]
    (change-status! conn report status :approved
                    (assoc opts :reason :expense-report-approved))))

(defn reject!
  "Reject a `:submitted` or `:approved` report. The ADR-038
   `:requires-non-empty-reason-note` policy fires — `:reason-note`
   is required.

   Required: :expense-report, :changed-by-uid, :reason-note.
   Optional: :vt-from, :vt-to."
  [conn {:keys [expense-report changed-by-uid reason-note] :as opts}]
  (when-not changed-by-uid (throw (ex-info ":changed-by-uid required" {})))
  (when-not reason-note    (throw (ex-info ":reason-note required" {})))
  (let [db (d/db conn)
        report (resolve-report db expense-report)
        _ (when-not report (throw (ex-info "Expense report not found"
                                           {:spec expense-report})))
        from (:expense-report/status
              (d/pull db [:expense-report/status] report))]
    (change-status! conn report from :rejected
                    (assoc opts :reason :expense-report-rejected))))

(defn reopen!
  "Reopen a `:rejected` report back to `:draft` so the employee can
   correct it and resubmit — the reset-to-draft path real expense
   workflows need (Odoo `hr.expense` has it; research note 09 §5).
   `add-line!` works again once the report is `:draft`.

   Required: :expense-report, :changed-by-uid.
   Optional: :reason-note, :vt-from, :vt-to."
  [conn {:keys [expense-report changed-by-uid] :as opts}]
  (when-not changed-by-uid (throw (ex-info ":changed-by-uid required" {})))
  (let [db (d/db conn)
        report (resolve-report db expense-report)
        _ (when-not report (throw (ex-info "Expense report not found"
                                           {:spec expense-report})))
        status (:expense-report/status
                (d/pull db [:expense-report/status] report))
        _ (when-not (= :rejected status)
            (throw (ex-info "Can only reopen a :rejected report"
                            {:type :expense/report-not-rejected :status status})))]
    (change-status! conn report :rejected :draft
                    (assoc opts :reason :expense-report-reopened))))

;; ============================================================================
;; post-report! — the GL entry
;; ============================================================================

(defn- credit-account-for
  [payment-mode {:keys [reimbursement-payable-account card-clearing-account]}]
  (case payment-mode
    :own-account     (or reimbursement-payable-account
                         (throw (ex-info ":reimbursement-payable-account required — the report has :own-account lines"
                                         {:type :expense/account-required})))
    :company-account (or card-clearing-account
                         (throw (ex-info ":card-clearing-account required — the report has :company-account lines"
                                         {:type :expense/account-required})))))

(defn post-report-tx-data
  "Pure tx-data builder for `post-report!` (ADR-068). Composes the
   posting tx-data + status-history + report→transaction link."
  [db {:keys [expense-report journal cost-center-plan posted-at
              changed-by-uid]
       :as opts}]
  (when-not journal (throw (ex-info ":journal required" {})))
  (let [report (resolve-report db expense-report)
        _ (when-not report (throw (ex-info "Expense report not found"
                                           {:spec expense-report})))
        r (d/pull db [:expense-report/status :expense-report/code] report)
        _ (when-not (= :approved (:expense-report/status r))
            (throw (ex-info "Can only post an :approved report"
                            {:type :expense/report-not-approved
                             :status (:expense-report/status r)})))
        lines (lines-of db report)
        _ (when (empty? lines)
            (throw (ex-info "Report has no lines to post" {:report report})))
        pa (or posted-at (Date.))
        ;; Debit postings — one per line.
        debit-postings
        (mapv (fn [l]
                (cond-> {:posting/account (:db/id (:expense-line/expense-account l))
                         :posting/amount (:expense-line/amount l)
                         :posting/commodity (:db/id (:expense-line/commodity l))
                         :posting/posted-at pa}
                  (and cost-center-plan (:expense-line/cost-center l))
                  (assoc :posting/analytic-distributions
                         [{:analytic-distribution/plan cost-center-plan
                           :analytic-distribution/account
                           (:db/id (:expense-line/cost-center l))
                           :analytic-distribution/percent 100M}])))
              lines)
        ;; Credit postings — grouped by (payment-mode, commodity).
        credit-postings
        (->> lines
             (group-by (juxt :expense-line/payment-mode
                             #(:db/id (:expense-line/commodity %))))
             (mapv (fn [[[mode commodity] grp]]
                     {:posting/account (credit-account-for mode opts)
                      :posting/amount (.negate ^BigDecimal
                                       (reduce (fn [^BigDecimal a l]
                                                 (.add a (:expense-line/amount l)))
                                               0M grp))
                      :posting/commodity commodity
                      :posting/posted-at pa})))
        tx-data (posting/build-transaction
                 {:transaction {:transaction/journal journal
                                :transaction/effective-date pa
                                :transaction/state :posted
                                :transaction/posted-at pa
                                :transaction/source
                                (str "expense-report:" (:expense-report/code r))
                                :transaction/narration
                                (str "Expense report " (:expense-report/code r))}
                  :postings (into debit-postings credit-postings)})
        status-tx (sm/record-status-change-tx-data
                   db (cond-> {:entity report
                               :entity-type :expense-report
                               :facet :expense-report/status
                               :from :approved :to :posted
                               :changed-at pa
                               :reason :expense-report-posted}
                        changed-by-uid (assoc :changed-by-uid changed-by-uid)))]
    (-> (vec tx-data)
        (into status-tx)
        (conj {:db/id report
               :expense-report/transaction -1}))))

(defn post-report!
  "Post an `:approved` report to the GL (`:approved → :posted`).
   Builds — in ONE transaction with the status change — a sealed
   journal entry: each line debits its `:expense-account`; the
   credit legs are grouped by `(payment-mode, commodity)` —
   `:own-account` → `:reimbursement-payable-account`,
   `:company-account` → `:card-clearing-account`. Stamps
   `:transaction/source` = `\"expense-report:<code>\"` and links
   `:expense-report/transaction`. Routes through the gate (ADR-068).

   When `:cost-center-plan` is supplied, a line's `:cost-center` is
   attached to its debit posting as a `:posting/analytic-distributions`
   entry (100%); without it the cost-center stays recorded on the
   line only (a documented follow-up).

   Required: :expense-report, :journal. Plus, per the payment-modes
   present: :reimbursement-payable-account and/or
   :card-clearing-account.
   Optional: :cost-center-plan, :posted-at (default now),
             :changed-by-uid, :vt-from, :vt-to.
   Returns the tx-report.

   The pure tx-data builder is `post-report-tx-data`."
  [conn {:keys [posted-at vt-from vt-to] :as opts}]
  (let [pa (or posted-at (Date.))]
    (validation/transact-with-validation
     conn (kbt/with-vt (post-report-tx-data
                        (d/db conn) (assoc opts :posted-at pa))
                       (or vt-from pa)
                       (or vt-to kbt/forever)))))

;; ============================================================================
;; reimburse! — settle an own-account report
;; ============================================================================

(defn reimburse-tx-data
  "Pure tx-data builder for `reimburse!` (ADR-068)."
  [db {:keys [expense-report journal cash-account
              reimbursement-payable-account posted-at changed-by-uid]}]
  (when-not journal (throw (ex-info ":journal required" {})))
  (when-not cash-account (throw (ex-info ":cash-account required" {})))
  (when-not reimbursement-payable-account
    (throw (ex-info ":reimbursement-payable-account required" {})))
  (let [report (resolve-report db expense-report)
        _ (when-not report (throw (ex-info "Expense report not found"
                                           {:spec expense-report})))
        status (:expense-report/status
                (d/pull db [:expense-report/status] report))
        _ (when-not (= :posted status)
            (throw (ex-info "Can only reimburse a :posted report"
                            {:type :expense/report-not-posted :status status})))
        own-lines (filter #(= :own-account (:expense-line/payment-mode %))
                          (lines-of db report))
        _ (when (empty? own-lines)
            (throw (ex-info "Report has no :own-account lines to reimburse"
                            {:type :expense/nothing-to-reimburse :report report})))
        pa (or posted-at (Date.))
        by-commodity
        (->> own-lines
             (group-by #(:db/id (:expense-line/commodity %)))
             (mapcat (fn [[commodity grp]]
                       (let [total (reduce (fn [^BigDecimal a l]
                                             (.add a (:expense-line/amount l)))
                                           0M grp)]
                         [{:posting/account reimbursement-payable-account
                           :posting/amount total
                           :posting/commodity commodity
                           :posting/posted-at pa}
                          {:posting/account cash-account
                           :posting/amount (.negate ^BigDecimal total)
                           :posting/commodity commodity
                           :posting/posted-at pa}])))
             vec)
        tx-data (posting/build-transaction
                 {:transaction {:transaction/journal journal
                                :transaction/effective-date pa
                                :transaction/state :posted
                                :transaction/posted-at pa
                                :transaction/narration "Expense reimbursement"}
                  :postings by-commodity})
        status-tx (sm/record-status-change-tx-data
                   db (cond-> {:entity report
                               :entity-type :expense-report
                               :facet :expense-report/status
                               :from :posted :to :reimbursed
                               :changed-at pa
                               :reason :expense-report-reimbursed}
                        changed-by-uid (assoc :changed-by-uid changed-by-uid)))]
    (-> (vec tx-data)
        (into status-tx)
        (conj {:db/id report
               :expense-report/reimbursement-transaction -1}))))

(defn reimburse!
  "Settle the `:own-account` portion of a `:posted` report
   (`:posted → :reimbursed`): builds — in ONE transaction with the
   status change — a sealed `Dr :reimbursement-payable-account /
   Cr :cash-account` entry for the own-account total, and links
   `:expense-report/reimbursement-transaction`. A report with no
   `:own-account` lines is terminal at `:posted` — `reimburse!`
   throws for it. Routes through the gate (ADR-068).

   Required: :expense-report, :journal, :cash-account,
             :reimbursement-payable-account.
   Optional: :posted-at (default now), :changed-by-uid,
             :vt-from, :vt-to.
   Returns the tx-report.

   The pure tx-data builder is `reimburse-tx-data`."
  [conn {:keys [posted-at vt-from vt-to] :as opts}]
  (let [pa (or posted-at (Date.))]
    (validation/transact-with-validation
     conn (kbt/with-vt (reimburse-tx-data
                        (d/db conn) (assoc opts :posted-at pa))
                       (or vt-from pa)
                       (or vt-to kbt/forever)))))

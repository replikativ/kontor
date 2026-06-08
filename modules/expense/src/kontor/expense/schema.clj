(ns kontor.expense.schema
  "kontor-expense companion schema — ADR-061 (employee expense reports).

   Entities:
     :expense-report  — a submission header grouping expense lines,
                        with the ADR-034 approval lifecycle
     :expense-line    — one expense (category, amount, payment-mode,
                        receipt, cost-center)

   The substrate is already 100% there — `:partner` is the employee,
   `:audit-doc` the receipt, the status machine + approval policy
   drive submit → approve → post, analytic distributions carry the
   cost-center, `build-transaction` composes the GL. kontor-expense
   adds two entities + transactors and touches the kernel not at all.

   Cohabits with the kernel + other companions per ADR-002."
  (:require [datahike.api :as d]))

;; ============================================================================
;; :expense-report
;; ============================================================================

(def ^:private expense-report-attrs
  [{:db/ident       :kontor.expense-report/code
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "External identifier — 'EXP-2026-0042'."}

   {:db/ident       :kontor.expense-report/employee
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Ref to :partner — the employee who incurred the
                     expenses. Also stamped as :kontor.audit/create-uid by
                     `create-report!` so the ADR-038
                     :no-self-approval rule fires (an employee
                     cannot approve their own report)."}

   {:db/ident       :kontor.expense-report/status
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "ADR-034 lifecycle facet.
                     #{:draft :submitted :approved :posted :reimbursed
                       :rejected}."}

   {:db/ident       :kontor.expense-report/report-date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.expense-report/commodity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "The report's reporting commodity. Individual
                     lines may carry their own :commodity."}

   {:db/ident       :kontor.expense-report/total
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one
    :db/doc         "Cached convenience total — the truth is
                     `Σ :kontor.expense-line/amount`, maintained by
                     `add-line!`. Meaningful only for a
                     single-commodity report — it sums raw amounts
                     across commodities, so a mixed-commodity
                     report's total is a meaningless number; the GL
                     entry `post-report!` builds is still correct
                     (it groups credit legs per commodity)."}

   {:db/ident       :kontor.expense-report/transaction
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "The GL entry — ref to :transaction. Set by
                     `post-report!`."}

   {:db/ident       :kontor.expense-report/reimbursement-transaction
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "The reimbursement settlement entry — ref to
                     :transaction. Set by `reimburse!` (own-account
                     reports only)."}

   {:db/ident       :kontor.expense-report/note
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}])

;; ============================================================================
;; :expense-line
;; ============================================================================

(def ^:private expense-line-attrs
  [{:db/ident       :kontor.expense-line/expense-report
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.expense-line/category
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Generic ref — the consumer's expense-category
                     entity (kontor ships the slot, not a
                     vocabulary)."}

   {:db/ident       :kontor.expense-line/expense-date
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.expense-line/amount
    :db/valueType   :db.type/bigdec
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.expense-line/commodity
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :kontor.expense-line/payment-mode
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "#{:own-account :company-account}. Decides the
                     credit leg of the GL entry: :own-account credits
                     an employee-reimbursement-payable (settled by
                     `reimburse!`); :company-account credits a
                     corporate-card-clearing account (closed by
                     bank-statement matching). A report may mix
                     modes across lines."}

   {:db/ident       :kontor.expense-line/expense-account
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "The P&L account this line debits."}

   {:db/ident       :kontor.expense-line/cost-center
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Optional ref to :analytic-account — the
                     cost-center the expense is attributed to."}

   {:db/ident       :kontor.expense-line/supporting-doc
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Ref to :audit-doc — the receipt. `submit!`
                     enforces its presence unless :require-receipts?
                     is false (e.g. a mileage line)."}

   {:db/ident       :kontor.expense-line/description
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}])

;; ============================================================================
;; Aggregate
;; ============================================================================

(def all
  (vec (concat expense-report-attrs expense-line-attrs)))

;; ============================================================================
;; Status-transition + approval-policy seeds (ADR-034 / ADR-038)
;; ============================================================================

(def status-transition-seeds
  "ADR-034 :status-transition rows for the :kontor.expense-report/status
   lifecycle."
  (vec
   (for [[from to name]
         [[:nil        :draft       "Create (draft)"]
          [:draft      :submitted   "Submit for approval"]
          [:submitted  :approved    "Approve"]
          [:submitted  :rejected    "Reject (submitted)"]
          [:approved   :rejected    "Reject (approved)"]
          [:rejected   :draft       "Reopen for correction"]
          [:approved   :posted      "Post to the GL"]
          [:posted     :reimbursed  "Reimburse (own-account)"]]]
     {:kontor.status-transition/entity-type :expense-report
      :kontor.status-transition/facet :kontor.expense-report/status
      :kontor.status-transition/from from
      :kontor.status-transition/to to
      :kontor.status-transition/active true
      :kontor.status-transition/name name})))

(def approval-policy-seeds
  "ADR-038 :approval-policy rows. Approval is the consequential
   transition — `:no-self-approval` (an employee cannot approve
   their own report; `create-report!` stamps the employee as
   `:kontor.audit/create-uid`). Both `:rejected` edges require a non-empty
   reason note."
  [{:kontor.approval-policy/entity-type     :expense-report
    :kontor.approval-policy/facet           :kontor.expense-report/status
    :kontor.approval-policy/transition-from :submitted
    :kontor.approval-policy/transition-to   :approved
    :kontor.approval-policy/rule            :no-self-approval
    :kontor.approval-policy/active          true}
   {:kontor.approval-policy/entity-type     :expense-report
    :kontor.approval-policy/facet           :kontor.expense-report/status
    :kontor.approval-policy/transition-from :submitted
    :kontor.approval-policy/transition-to   :rejected
    :kontor.approval-policy/rule            :requires-non-empty-reason-note
    :kontor.approval-policy/active          true}
   {:kontor.approval-policy/entity-type     :expense-report
    :kontor.approval-policy/facet           :kontor.expense-report/status
    :kontor.approval-policy/transition-from :approved
    :kontor.approval-policy/transition-to   :rejected
    :kontor.approval-policy/rule            :requires-non-empty-reason-note
    :kontor.approval-policy/active          true}])

;; ============================================================================
;; Installer
;; ============================================================================

(defn install!
  "Install the kontor-expense schema + status-transition + approval-
   policy seeds. Idempotent for the schema attrs; the seeds are
   guarded with a presence check (the composite-tuple-with-nil-in-
   tuple non-idempotency caveat).

   Run after kontor.core/install-schema! — kontor-expense references
   kernel attrs (:partner, :account, :analytic-account, :commodity,
   :audit-doc, :transaction, :kontor.audit/create-uid, :status-transition)."
  [conn]
  (d/transact conn all)
  (let [db (d/db conn)
        already? (boolean
                  (d/q '[:find ?e .
                         :where [?e :kontor.status-transition/entity-type :expense-report]]
                       db))]
    (when-not already?
      (d/transact conn (vec (concat status-transition-seeds
                                    approval-policy-seeds)))))
  conn)

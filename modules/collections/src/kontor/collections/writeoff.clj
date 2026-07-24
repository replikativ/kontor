(ns kontor.collections.writeoff
  "Bad-debt write-off transactor — ADR-043.

   Composes:
     1. Drives :kontor.collection-case/state → :written-off via the status
        machine (typically from :legal; the seed allows it).
     2. For each remaining-open invoice on the case, posts a
        Dr Bad-Debt-Expense / Cr AR kernel transaction per
        kontor.posting/build-transaction.
     3. Writes a supporting :audit-doc.

   Bad-debt-expense + AR account resolution uses the same
   :gl-account-default three-tier pattern as the invoice bridge
   (ADR-036, ADR-041 :account-type-direction)."
  (:require [datahike.api :as d]
            [kontor.bitemporal :as kbt]
            [kontor.collections.case :as kcase]
            [kontor.banking.payment-application :as papp]
            [kontor.posting :as posting]
            [kontor.workflow.status-machine :as sm]
            [kontor.validation :as validation]))

;; ============================================================================
;; Helpers
;; ============================================================================

(defn- resolve-account
  "Three-tier GL resolve same shape as kontor.invoice.posting/
   resolve-gl-account: explicit override → entity-default → tenant-
   default → throw."
  [db {:keys [override-account account-type entity]}]
  (or override-account
      (when entity
        (d/q '[:find ?a .
               :in $ ?at ?e
               :where
               [?d :kontor.gl-account-default/account-type ?at]
               [?d :kontor.gl-account-default/entity ?e]
               [?d :kontor.gl-account-default/account ?a]]
             db account-type entity))
      (d/q '[:find ?a .
             :in $ ?at
             :where
             [?d :kontor.gl-account-default/account-type ?at]
             [?d :kontor.gl-account-default/account ?a]
             [(missing? $ ?d :kontor.gl-account-default/entity)]]
           db account-type)
      (throw (ex-info "No :gl-account-default configured for account-type"
                      {:type :writeoff/missing-gl-default
                       :account-type account-type
                       :entity entity}))))

(defn- resolve-commodity [db sym]
  (when sym
    (d/q '[:find ?c .
           :in $ ?sym
           :where [?c :kontor.commodity/symbol ?sym]]
         db sym)))

(defn- invoice-tx-eid
  "The kernel `:transaction` the invoice was booked as, or nil for an
   invoice that never hit the GL."
  [db invoice-eid]
  (:db/id (:kontor.invoice/transaction
           (d/pull db [{:kontor.invoice/transaction [:db/id]}] invoice-eid))))

(defn- open-invoices-for-case
  "Sales invoices linked to the case's partner + entity that are not
   yet fully :paid. Returns vec of {:invoice-eid :open-amount
   :commodity-sym}."
  [db case-eid]
  (let [c (d/pull db
                  [{:kontor.collection-case/partner [:db/id]}
                   {:kontor.collection-case/entity  [:db/id]}]
                  case-eid)
        partner-eid (get-in c [:kontor.collection-case/partner :db/id])
        entity-eid  (get-in c [:kontor.collection-case/entity :db/id])
        eids (d/q '[:find [?i ...]
                    :in $ ?b ?e
                    :where
                    [?i :kontor.invoice/buyer ?b]
                    [?i :kontor.invoice/entity ?e]
                    (or [?i :kontor.invoice/status :sent]
                        [?i :kontor.invoice/status :partially-paid])]
                  db partner-eid entity-eid)]
    (->> eids
         (map (fn [eid]
                {:invoice-eid eid
                 :open-amount (papp/open-amount-of-invoice db eid)
                 :commodity-sym (:kontor.invoice/currency
                                 (d/pull db [:kontor.invoice/currency] eid))}))
         (filter #(pos? (.signum ^java.math.BigDecimal (:open-amount %))))
         vec)))

;; ============================================================================
;; Transactor
;; ============================================================================

(declare write-off-case-tx-data)

(defn write-off-case!
  "Write off the remaining open balance of every open invoice on a
   case. Atomically:
     - For each open invoice on the case, build a kernel :transaction
       Dr :bad-debt-expense / Cr :ar at the invoice's open-amount,
       linked to the invoice's own GL transaction via
       `:kontor.transaction/settles`.
       (Multi-invoice cases produce N transactions, all in one tx.)
     - For each, ALSO close the AR open item: a `:payment-application`
       row carrying the amount as `:write-off-amount`, which drives the
       invoice's `:kontor.invoice/status` to `:paid`. Without it the
       subledger keeps reporting the invoice fully open while the GL
       receivable is already relieved (note 198 audit HIGH-3).
     - Drive :kontor.collection-case/state → :written-off via the status
       machine (must be legal from current state — typically requires
       case at :legal).
     - Write :audit-doc with :type :write-off-supporting; ref it on
       the status-history row.

   Writing off a case whose invoices are all already closed is a no-op
   on the ledger: `:invoices-written-off` is 0 and no posting is made.

   Required opts:
     :case            ref/eid/code
     :written-off-by  ref to :kontor.audit/create-uid
     :journal-ref     [:kontor.journal/code \"..\"]
     :reason          keyword (e.g. :uncollectible-90-days)
     :supporting-doc  ref to :audit-doc (provide a pre-uploaded doc
                      with the manager's sign-off + customer outreach
                      log).

   Optional opts:
     :reason-note     free-text
     :ledger-ref      override the primary ledger
     :effective-date  instant (default now)
     :vt-from         valid-time start (default :effective-date)
     :vt-to           valid-time end (default :kbt/forever)

   Returns {:tx-report :case-eid :invoices-written-off :total-written-off}.

   The pure tx-data builder is `write-off-case-tx-data`."
  [conn {:keys [vt-from vt-to effective-date case] :as opts}]
  (let [effective-date (or effective-date (java.util.Date.))
        opts' (assoc opts :effective-date effective-date)
        db (d/db conn)
        case-eid (kcase/resolve-case db case)
        opens (when case-eid (open-invoices-for-case db case-eid))
        tx-data (write-off-case-tx-data db opts')
        tx-report (validation/transact-with-validation
                   conn (kbt/with-vt tx-data
                                     (or vt-from effective-date)
                                     (or vt-to kbt/forever)))]
    {:tx-report tx-report
     :case-eid case-eid
     :invoices-written-off (count opens)
     :total-written-off (reduce (fn [^java.math.BigDecimal acc {:keys [open-amount]}]
                                  (.add acc ^java.math.BigDecimal open-amount))
                                0M opens)}))

(defn write-off-case-tx-data
  "Pure tx-data builder for `write-off-case!` (ADR-068). Optional
   `:effective-date` (default now)."
  [db {:keys [case written-off-by journal-ref reason supporting-doc
              reason-note ledger-ref effective-date]}]
  (when-not case            (throw (ex-info ":case required" {})))
  (when-not written-off-by  (throw (ex-info ":written-off-by required" {})))
  (when-not journal-ref     (throw (ex-info ":journal-ref required" {})))
  (when-not reason          (throw (ex-info ":reason required" {})))
  (when-not supporting-doc  (throw (ex-info ":supporting-doc required" {})))
  (let [case-eid (kcase/resolve-case db case)
        _ (when-not case-eid (throw (ex-info "Case not found" {:spec case})))
        c (d/pull db
                  '[* {:kontor.collection-case/partner [:db/id]
                       :kontor.collection-case/entity  [:db/id]}]
                  case-eid)
        entity-eid (get-in c [:kontor.collection-case/entity :db/id])
        partner-eid (get-in c [:kontor.collection-case/partner :db/id])
        opens (open-invoices-for-case db case-eid)
        effective-date (or effective-date (java.util.Date.))
        ;; Build kernel postings per invoice. Each transaction is its
        ;; own kernel build-transaction call; we offset tempids per
        ;; invoice so they cohabit.
        ;; Approach: one transaction per (entity, commodity) pair
        ;; combining all open amounts on that pair. Simpler is one
        ;; per invoice; for v1 we use the simpler form.
        per-invoice-txs
        (map-indexed
         (fn [idx {:keys [invoice-eid open-amount commodity-sym]}]
           (let [commodity-eid (resolve-commodity db commodity-sym)
                 _ (when-not commodity-eid
                     (throw (ex-info "Invoice commodity unresolvable"
                                     {:invoice invoice-eid
                                      :commodity-sym commodity-sym})))
                 ar (resolve-account db {:account-type :ar
                                         :entity entity-eid})
                 bad-debt (resolve-account db {:account-type :bad-debt-expense
                                               :entity entity-eid})
                 inv-tx (invoice-tx-eid db invoice-eid)
                 input {:transaction (cond-> {:kontor.transaction/journal journal-ref
                                              :kontor.transaction/effective-date effective-date
                                              :kontor.transaction/state :posted
                                              :kontor.transaction/posted-at effective-date
                                              :kontor.transaction/narration
                                              (str "Write-off invoice #" invoice-eid
                                                   " " open-amount " "
                                                   commodity-sym)}
                                       partner-eid
                                       (assoc :kontor.transaction/partner partner-eid)
                                       ;; note 198 audit HIGH-3. The write-off RELIEVES the
                                       ;; receivable, so it must link to the transaction that
                                       ;; raised it — kernel aging
                                       ;; (`reconciliation/open-receivables-by-tx`) nets an
                                       ;; open item by walking
                                       ;; `[?settler :kontor.transaction/settles ?settled]`.
                                       ;; Same reasoning as HIGH-4 on `settle-invoice!`.
                                       inv-tx
                                       (assoc :kontor.transaction/settles [inv-tx]))
                        :postings [(cond-> {:kontor.posting/account bad-debt
                                            :kontor.posting/amount open-amount
                                            :kontor.posting/commodity commodity-eid
                                            :kontor.posting/partner partner-eid}
                                     entity-eid (assoc :kontor.posting/entity entity-eid)
                                     ledger-ref (assoc :kontor.posting/ledger ledger-ref))
                                   (cond-> {:kontor.posting/account ar
                                            :kontor.posting/amount (.negate
                                                             ^java.math.BigDecimal
                                                             open-amount)
                                            :kontor.posting/commodity commodity-eid
                                            :kontor.posting/partner partner-eid}
                                     entity-eid (assoc :kontor.posting/entity entity-eid)
                                     ledger-ref (assoc :kontor.posting/ledger ledger-ref))]}
                 raw (posting/build-transaction input)
                 ;; build-transaction uses -1 for tx-tempid and -300+
                 ;; for postings. Walk and offset by idx.
                 offset (* 1000 idx)
                 shifted (clojure.walk/postwalk
                          (fn [x]
                            (if (and (integer? x) (neg? x)
                                     (not (instance? java.math.BigInteger x)))
                              (- x offset)
                              x))
                          raw)
                 wo-tx-tempid (- -1 offset)
                 ;; note 198 audit HIGH-3. The GL leg above relieves AR, but the
                 ;; AR SUBLEDGER is `open-amount-of-invoice` = gross − applied,
                 ;; and nothing here was ever applied. So the write-off left the
                 ;; invoice reading fully open at status :sent — exactly the two
                 ;; predicates `open-invoices-for-case` selects on — and a second
                 ;; collections case wrote the SAME invoice off AGAIN: GL AR
                 ;; −2,000 / bad-debt +2,000 against a 1,000 invoice, subledger
                 ;; still 1,000. Close the open item with a `:payment-application`
                 ;; carrying the amount as `:write-off-amount` (the schema's own
                 ;; stated use for this path: "closes the remaining open amount
                 ;; ... as PART of the settlement"), which also drives the
                 ;; invoice status to :paid. `:amount` is 0 because NO CASH was
                 ;; applied; the whole open balance closes as a write-off.
                 app-tx (papp/apply-payment-tx-data
                         db (cond-> {:payment wo-tx-tempid
                                     :invoice invoice-eid
                                     :amount 0M
                                     :write-off-amount open-amount
                                     :write-off-account bad-debt
                                     :commodity commodity-eid
                                     :applied-by-uid written-off-by
                                     :applied-at effective-date
                                     :strategy :write-off
                                     :reason reason
                                     :tempid-suffix (str "-wo-" idx)}
                              reason-note    (assoc :reason-note reason-note)
                              supporting-doc (assoc :supporting-doc supporting-doc)))]
             {:tx-tempid wo-tx-tempid
              :tx-data (into (vec shifted) app-tx)}))
         opens)
        all-posting-tx (vec (mapcat :tx-data per-invoice-txs))
        ;; Status transition + supporting-doc ref. The transition
        ;; itself can fire `to :written-off` from `:legal` per the
        ;; schema seeds.
        status-tx (sm/record-status-change-tx-data
                   db
                   {:entity case-eid
                    :entity-type :collection-case
                    :facet :kontor.collection-case/state
                    :to :written-off
                    :changed-at effective-date
                    :changed-by-uid written-off-by
                    :reason reason
                    :reason-note reason-note
                    :supporting-doc supporting-doc})
        ;; Also stamp the supporting-doc on the case + closed-at.
        case-update {:db/id case-eid
                     :kontor.collection-case/supporting-doc supporting-doc
                     :kontor.collection-case/closed-at effective-date}]
    (vec (concat all-posting-tx [case-update] status-tx))))

(ns kontor.collections.writeoff
  "Bad-debt write-off transactor — ADR-043.

   Composes:
     1. Drives :collection-case/state → :written-off via the status
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
            [kontor.payment-application :as papp]
            [kontor.posting :as posting]
            [kontor.status-machine :as sm]))

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
               [?d :gl-account-default/account-type ?at]
               [?d :gl-account-default/entity ?e]
               [?d :gl-account-default/account ?a]]
             db account-type entity))
      (d/q '[:find ?a .
             :in $ ?at
             :where
             [?d :gl-account-default/account-type ?at]
             [?d :gl-account-default/account ?a]
             [(missing? $ ?d :gl-account-default/entity)]]
           db account-type)
      (throw (ex-info "No :gl-account-default configured for account-type"
                      {:type :writeoff/missing-gl-default
                       :account-type account-type
                       :entity entity}))))

(defn- resolve-commodity [db sym]
  (when sym
    (d/q '[:find ?c .
           :in $ ?sym
           :where [?c :commodity/symbol ?sym]]
         db sym)))

(defn- open-invoices-for-case
  "Sales invoices linked to the case's partner + entity that are not
   yet fully :paid. Returns vec of {:invoice-eid :open-amount
   :commodity-sym}."
  [db case-eid]
  (let [c (d/pull db
                  [{:collection-case/partner [:db/id]}
                   {:collection-case/entity  [:db/id]}]
                  case-eid)
        partner-eid (get-in c [:collection-case/partner :db/id])
        entity-eid  (get-in c [:collection-case/entity :db/id])
        eids (d/q '[:find [?i ...]
                    :in $ ?b ?e
                    :where
                    [?i :invoice/buyer ?b]
                    [?i :invoice/entity ?e]
                    (or [?i :invoice/status :sent]
                        [?i :invoice/status :partially-paid])]
                  db partner-eid entity-eid)]
    (->> eids
         (map (fn [eid]
                {:invoice-eid eid
                 :open-amount (papp/open-amount-of-invoice db eid)
                 :commodity-sym (:invoice/currency
                                 (d/pull db [:invoice/currency] eid))}))
         (filter #(pos? (.signum ^java.math.BigDecimal (:open-amount %))))
         vec)))

;; ============================================================================
;; Transactor
;; ============================================================================

(defn write-off-case!
  "Write off the remaining open balance of every open invoice on a
   case. Atomically:
     - For each open invoice on the case, build a kernel :transaction
       Dr :bad-debt-expense / Cr :ar at the invoice's open-amount.
       (Multi-invoice cases produce N transactions, all in one tx.)
     - Drive :collection-case/state → :written-off via the status
       machine (must be legal from current state — typically requires
       case at :legal).
     - Write :audit-doc with :type :write-off-supporting; ref it on
       the status-history row.

   Required opts:
     :case            ref/eid/code
     :written-off-by  ref to :create/uid
     :journal-ref     [:journal/code \"..\"]
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

   Returns {:tx-report :transaction-eids :case-eid}."
  [conn {:keys [case written-off-by journal-ref reason supporting-doc
                reason-note ledger-ref effective-date vt-from vt-to]}]
  (when-not case            (throw (ex-info ":case required" {})))
  (when-not written-off-by  (throw (ex-info ":written-off-by required" {})))
  (when-not journal-ref     (throw (ex-info ":journal-ref required" {})))
  (when-not reason          (throw (ex-info ":reason required" {})))
  (when-not supporting-doc  (throw (ex-info ":supporting-doc required" {})))
  (let [db (d/db conn)
        case-eid (kcase/resolve-case db case)
        _ (when-not case-eid (throw (ex-info "Case not found" {:spec case})))
        c (d/pull db
                  '[* {:collection-case/partner [:db/id]
                       :collection-case/entity  [:db/id]}]
                  case-eid)
        entity-eid (get-in c [:collection-case/entity :db/id])
        partner-eid (get-in c [:collection-case/partner :db/id])
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
                 tx-tempid (- -100 (* idx 10))
                 dr-tempid (- tx-tempid 1)
                 cr-tempid (- tx-tempid 2)
                 input {:transaction (cond-> {:transaction/journal journal-ref
                                              :transaction/effective-date effective-date
                                              :transaction/state :posted
                                              :transaction/posted-at effective-date
                                              :transaction/narration
                                              (str "Write-off invoice #" invoice-eid
                                                   " " open-amount " "
                                                   commodity-sym)}
                                       partner-eid
                                       (assoc :transaction/partner partner-eid))
                        :postings [(cond-> {:posting/account bad-debt
                                            :posting/amount open-amount
                                            :posting/commodity commodity-eid
                                            :posting/partner partner-eid}
                                     entity-eid (assoc :posting/entity entity-eid)
                                     ledger-ref (assoc :posting/ledger ledger-ref))
                                   (cond-> {:posting/account ar
                                            :posting/amount (.negate
                                                             ^java.math.BigDecimal
                                                             open-amount)
                                            :posting/commodity commodity-eid
                                            :posting/partner partner-eid}
                                     entity-eid (assoc :posting/entity entity-eid)
                                     ledger-ref (assoc :posting/ledger ledger-ref))]}
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
                          raw)]
             {:tx-tempid (- -1 offset)
              :tx-data shifted}))
         opens)
        all-posting-tx (vec (mapcat :tx-data per-invoice-txs))
        ;; Status transition + supporting-doc ref. The transition
        ;; itself can fire `to :written-off` from `:legal` per the
        ;; schema seeds.
        status-tx (sm/record-status-change-tx-data
                   db
                   {:entity case-eid
                    :entity-type :collection-case
                    :facet :collection-case/state
                    :to :written-off
                    :changed-at effective-date
                    :changed-by-uid written-off-by
                    :reason reason
                    :reason-note reason-note
                    :supporting-doc supporting-doc})
        ;; Also stamp the supporting-doc on the case + closed-at.
        case-update {:db/id case-eid
                     :collection-case/supporting-doc supporting-doc
                     :collection-case/closed-at effective-date}
        all-tx (vec (concat all-posting-tx [case-update] status-tx))
        tx-report (d/transact conn (kbt/with-vt all-tx
                                                (or vt-from effective-date)
                                                (or vt-to kbt/forever)))]
    {:tx-report tx-report
     :case-eid case-eid
     :invoices-written-off (count opens)
     :total-written-off (reduce (fn [^java.math.BigDecimal acc {:keys [open-amount]}]
                                  (.add acc ^java.math.BigDecimal open-amount))
                                0M opens)}))

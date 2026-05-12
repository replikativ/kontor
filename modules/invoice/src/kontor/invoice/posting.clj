(ns kontor.invoice.posting
  "Order → Invoice → AcctgTrans posting bridge — ADR-036.

   Three-tier GL resolution:
     1. Explicit override on the invoice line (:invoice-line/account
        — kernel attribute).
     2. Entity-specific default — :gl-account-default with
        :gl-account-default/entity = :invoice/entity.
     3. Tenant-wide default — :gl-account-default with no entity.
     4. Throw :invoice/missing-gl-default if all miss.

   The bridge builds one kernel :transaction per invoice (the invoice
   IS the posting unit; per-line :posting entries reference the same
   transaction). Routes through kontor.posting/build-transaction so
   the kernel sum-to-zero + period-locked + commodity-required
   invariants are all enforced."
  (:require [datahike.api :as d]
            [kontor.ledger :as ledger]
            [kontor.period :as period]
            [kontor.posting :as posting]
            [kontor.status-machine :as sm]))

;; ============================================================================
;; Three-tier GL resolution
;; ============================================================================

(defn- entity-default-account
  "Query :gl-account-default for the given (account-type, entity)."
  [db account-type entity-eid]
  (when entity-eid
    (d/q '[:find ?a .
           :in $ ?at ?e
           :where
           [?d :gl-account-default/account-type ?at]
           [?d :gl-account-default/entity ?e]
           [?d :gl-account-default/account ?a]]
         db account-type entity-eid)))

(defn- tenant-wide-default-account
  "Query :gl-account-default for the given account-type with no
   entity scope."
  [db account-type]
  (d/q '[:find ?a .
         :in $ ?at
         :where
         [?d :gl-account-default/account-type ?at]
         [?d :gl-account-default/account ?a]
         [(missing? $ ?d :gl-account-default/entity)]]
       db account-type))

(defn resolve-gl-account
  "Three-tier resolve. Returns the :account entity-id, or throws
   ex-info :type :invoice/missing-gl-default if all three tiers
   miss.

   Args:
     db          — datahike db value
     opts        — map with:
       :override-account  optional :account ref (kernel :invoice-line/account)
       :account-type      keyword (e.g. :sales-revenue)
       :entity            optional :entity ref"
  [db {:keys [override-account account-type entity]}]
  (or override-account
      (entity-default-account db account-type entity)
      (tenant-wide-default-account db account-type)
      (throw (ex-info "No GL account configured for account-type"
                      {:type           :invoice/missing-gl-default
                       :account-type   account-type
                       :entity         entity
                       :remediation    "Seed a :gl-account-default row
                                        for this (account-type, entity)
                                        OR set :invoice-line/account
                                        explicitly on the line."}))))

;; ============================================================================
;; Debit/credit direction for (invoice-type, account-type)
;; ============================================================================

(defn- default-direction-for
  "Built-in fallback debit/credit direction map. ADR-041 introduces
   the `:account-type-direction` kernel table for extension; this fn
   serves as the fallback when no row is seeded for an
   (invoice-type, account-type) pair."
  [invoice-type account-type]
  (let [sales-credit    #{:sales-revenue :sales-revenue-deferred
                          :sales-tax-payable :shipping-income
                          :withholding-tax-payable}
        sales-debit     #{:discount-given :ar :cogs
                          :withholding-tax-recoverable}
        purchase-credit #{:ap :purchase-discount
                          :withholding-tax-payable}
        purchase-debit  #{:purchase-expense :purchase-tax-recoverable
                          :shipping-expense :inventory
                          :withholding-tax-recoverable}]
    (case invoice-type
      :sales        (cond (contains? sales-credit account-type) :credit
                          (contains? sales-debit  account-type) :debit)
      :purchase     (cond (contains? purchase-credit account-type) :credit
                          (contains? purchase-debit  account-type) :debit)
      :credit-memo  (default-direction-for :sales account-type)
      :debit-memo   (default-direction-for :purchase account-type)
      nil)))

(defn debit-credit-for
  "Look up debit/credit direction in the kernel :account-type-direction
   table; fall back to the built-in default map (ADR-041 pattern).

   Throws if neither table nor default knows the (invoice-type,
   account-type) pair — consumers extending the vocabulary (e.g.
   procurement adding :goods-receipt-accrual) must seed a row."
  [db invoice-type account-type]
  (or (d/q '[:find ?dir .
             :in $ ?it ?at
             :where
             [?r :account-type-direction/invoice-type ?it]
             [?r :account-type-direction/account-type ?at]
             [?r :account-type-direction/direction ?dir]
             [?r :account-type-direction/active true]]
           db invoice-type account-type)
      (default-direction-for invoice-type account-type)
      (throw (ex-info "Unknown (invoice-type, account-type) for debit/credit"
                      {:type :invoice/unknown-account-type-direction
                       :invoice-type invoice-type
                       :account-type account-type
                       :remediation "Seed a :account-type-direction
                                     row for this pair, or extend the
                                     default map in kontor.invoice.
                                     posting/default-direction-for."}))))

;; ============================================================================
;; Posting + transaction construction
;; ============================================================================

(defn- resolve-commodity-eid
  "Resolve the :commodity eid from the kernel `:invoice/currency`
   string (an ISO-4217 code or commodity symbol)."
  [db currency-str]
  (when currency-str
    (d/q '[:find ?c .
           :in $ ?sym
           :where [?c :commodity/symbol ?sym]]
         db currency-str)))

(defn- build-input
  "Build the input map for kontor.posting/build-transaction. Includes:
   - :transaction map with :journal, :effective-date, :state :posted,
     :posted-at, optional :external-id, optional :partner
   - :postings vec with :amount, :account, :commodity, :partner,
     and optionally :entity + :ledger"
  [db invoice-eid {:keys [journal-ref ledger-ref posted-at]}]
  (let [invoice (d/pull db
                        '[* {:invoice/entity [:db/id]
                             :invoice/buyer  [:db/id]
                             :invoice/seller [:db/id]}]
                        invoice-eid)
        invoice-type (:invoice/type invoice)
        entity-eid   (get-in invoice [:invoice/entity :db/id])
        partner-eid  (case invoice-type
                       :sales       (get-in invoice [:invoice/buyer :db/id])
                       :purchase    (get-in invoice [:invoice/seller :db/id])
                       :credit-memo (get-in invoice [:invoice/buyer :db/id])
                       :debit-memo  (get-in invoice [:invoice/seller :db/id]))
        commodity-eid (resolve-commodity-eid db (:invoice/currency invoice))
        _ (when-not commodity-eid
            (throw (ex-info "Invoice currency not resolvable to :commodity"
                            {:type :invoice/unknown-commodity
                             :invoice-eid invoice-eid
                             :currency (:invoice/currency invoice)
                             :remediation "Seed a :commodity with the
                                          matching :commodity/symbol
                                          before posting."})))
        ledger-eid (or ledger-ref (ledger/primary db))
        lines (->> (d/q '[:find [?l ...]
                          :in $ ?inv
                          :where [?l :invoice-line/invoice ?inv]]
                        db invoice-eid)
                   (map #(d/pull db '[*] %))
                   (sort-by :invoice-line/sequence)
                   ;; Skip zero-amount lines (e.g. fully-billed remainder).
                   (remove (fn [l] (zero? (.signum ^java.math.BigDecimal
                                                   (or (:invoice-line/amount l) 0M))))))
        line-postings
        (mapv (fn [line]
                (let [account-type (:invoice-line/gl-account-type line)
                      override     (get-in line [:invoice-line/account :db/id])
                      amount       (:invoice-line/amount line)
                      account      (resolve-gl-account
                                    db {:override-account override
                                        :account-type account-type
                                        :entity entity-eid})
                      direction    (debit-credit-for db invoice-type account-type)
                      signed-amt   (if (= direction :debit)
                                     amount
                                     (.negate ^java.math.BigDecimal amount))]
                  (cond-> {:posting/account account
                           :posting/amount signed-amt
                           :posting/commodity commodity-eid
                           :posting/partner partner-eid}
                    entity-eid (assoc :posting/entity entity-eid)
                    ledger-eid (assoc :posting/ledger ledger-eid))))
              lines)
        contra-account-type (case invoice-type
                              :sales       :ar
                              :purchase    :ap
                              :credit-memo :ar
                              :debit-memo  :ap)
        line-sum (reduce (fn [acc {:posting/keys [amount]}]
                           (.add ^java.math.BigDecimal acc amount))
                         0M line-postings)
        contra-amount (.negate ^java.math.BigDecimal line-sum)
        contra-account (resolve-gl-account
                        db {:account-type contra-account-type
                            :entity entity-eid})
        contra-posting (cond-> {:posting/account contra-account
                                :posting/amount contra-amount
                                :posting/commodity commodity-eid
                                :posting/partner partner-eid}
                         entity-eid (assoc :posting/entity entity-eid)
                         ledger-eid (assoc :posting/ledger ledger-eid))]
    {:transaction (cond-> {:transaction/journal journal-ref
                           :transaction/effective-date posted-at
                           :transaction/state :posted
                           :transaction/posted-at posted-at
                           :transaction/external-id (:invoice/external-id invoice)}
                    partner-eid (assoc :transaction/partner partner-eid))
     :postings (conj line-postings contra-posting)}))

;; ============================================================================
;; Orchestrator
;; ============================================================================

(defn post-to-ledger!
  "Post the invoice to the GL atomically.

   In one tx:
     1. Verifies the invoice is :draft or :ready (status-machine
        legality check will reject otherwise).
     2. Verifies the target effective-date is not in a locked period
        (ADR-014; raises :period/locked).
     3. Builds a :transaction (state :posted, posted-at set) + per-
        line :posting entries via kontor.posting/build-transaction
        (commodity required, ledger explicit, sum-to-zero enforced).
     4. Sets :invoice/transaction + :invoice/posted-at on the invoice.
     5. Writes a :status-history row for the :draft|:ready → :sent
        transition.

   Returns {:tx-report ... :transaction-eid ... :invoice-eid ...}.

   Required opts:
     :journal-ref   ref or lookup-ref to the :journal entity for this
                    transaction. Required because the kernel posting
                    invariant rejects journal-less transactions.

   Optional opts:
     :ledger-ref         specific :ledger ref. Defaults to the
                         kernel's primary ledger.
     :posted-at          instant (default now).
     :changed-by-uid     optional ref to :create/uid.
     :reason             optional rationale (status-history)."
  [conn invoice-spec & [opts]]
  (let [db (d/db conn)
        invoice-eid (cond
                      (string? invoice-spec)
                      (d/q '[:find ?e . :in $ ?xid
                             :where [?e :invoice/external-id ?xid]]
                           db invoice-spec)
                      :else invoice-spec)
        _ (when-not invoice-eid
            (throw (ex-info "Invoice not found" {:spec invoice-spec})))
        _ (when-not (:journal-ref opts)
            (throw (ex-info "post-to-ledger! requires :journal-ref opt"
                            {:type :invoice/missing-journal
                             :remediation "Pass :journal-ref [:journal/code \"GL\"] (or your tenant's journal code) to post-to-ledger!. The kernel posting invariant rejects journal-less transactions per ADR-021."})))
        posted-at (or (:posted-at opts) (java.util.Date.))
        input (build-input db invoice-eid
                           {:journal-ref (:journal-ref opts)
                            :ledger-ref  (:ledger-ref opts)
                            :posted-at   posted-at})
        tx-data (posting/build-transaction input)
        ;; The transaction tempid is -1 by build-transaction convention.
        tx-tempid -1
        ;; Compose the bridge tx atomically: kernel posting tx-data +
        ;; invoice update (transaction ref + posted-at) + status-
        ;; history row for :draft|:ready → :sent.
        invoice-update {:db/id invoice-eid
                        :invoice/transaction tx-tempid
                        :invoice/posted-at posted-at}
        status-tx (sm/record-status-change-tx-data
                   db
                   (cond-> {:entity invoice-eid
                            :entity-type :invoice
                            :facet :invoice/status
                            :to :sent
                            :changed-at posted-at
                            :origin-transaction tx-tempid}
                     (:changed-by-uid opts) (assoc :changed-by-uid (:changed-by-uid opts))
                     (:reason opts) (assoc :reason (:reason opts))))
        all-tx (vec (concat tx-data [invoice-update] status-tx))
        ;; P0-7 (research-agent finding): assert no proposed posting
        ;; falls in a closed period. period/find-violations walks the
        ;; tx-data and extracts :posting/valid-from from each posting.
        _ (period/assert-not-in-locked-period! db all-tx)
        tx-report (d/transact conn all-tx)
        transaction-eid (get-in tx-report [:tempids tx-tempid])]
    {:tx-report tx-report
     :transaction-eid transaction-eid
     :invoice-eid invoice-eid}))

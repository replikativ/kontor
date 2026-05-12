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
   transaction)."
  (:require [datahike.api :as d]
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
;; Posting construction
;; ============================================================================

(defn- debit-credit-for
  "Compute debit/credit direction for a line, based on (invoice-type,
   account-type). Returns :debit or :credit.

   Sales invoice:
     :sales-revenue       → credit
     :sales-tax-payable   → credit
     :shipping-income     → credit
     :discount-given      → debit  (contra-revenue)
     :ar                  → debit  (the cash claim)
     :cogs                → debit
     :inventory           → credit (relief)

   Purchase invoice:
     mirror the above (debit becomes credit and vice versa)."
  [invoice-type account-type]
  (let [sales-credit #{:sales-revenue :sales-tax-payable :shipping-income}
        sales-debit  #{:discount-given :ar :cogs}
        purchase-credit #{:ap :purchase-discount}
        purchase-debit  #{:purchase-expense :purchase-tax-recoverable :shipping-expense :inventory}]
    (case invoice-type
      :sales        (cond (contains? sales-credit account-type) :credit
                          (contains? sales-debit  account-type) :debit
                          :else (throw (ex-info "Unknown account-type for :sales invoice"
                                                {:account-type account-type})))
      :purchase     (cond (contains? purchase-credit account-type) :credit
                          (contains? purchase-debit  account-type) :debit
                          :else (throw (ex-info "Unknown account-type for :purchase invoice"
                                                {:account-type account-type})))
      :credit-memo  (debit-credit-for :sales account-type)    ; reversed via amount sign
      :debit-memo   (debit-credit-for :purchase account-type)
      (throw (ex-info "Unknown invoice-type" {:invoice-type invoice-type})))))

(defn build-postings
  "Build :posting tx-data for an invoice. Returns a vector of posting
   tempid maps that reference a parent :transaction tempid.

   Args:
     db          — datahike db
     invoice-eid — entity-id of the :invoice
     tx-tempid   — string tempid that the postings will reference

   Resolves the three-tier GL account for each line and builds the
   double-entry. The AR/AP line is constructed implicitly from the
   non-AR/non-AP lines' sum (the contra side)."
  [db invoice-eid tx-tempid]
  (let [invoice (d/pull db
                        '[* {:invoice/entity [:db/id]
                             :invoice/buyer [:db/id]
                             :invoice/seller [:db/id]}]
                        invoice-eid)
        invoice-type (:invoice/type invoice)
        entity-eid (get-in invoice [:invoice/entity :db/id])
        partner-eid (case invoice-type
                      :sales       (get-in invoice [:invoice/buyer :db/id])
                      :purchase    (get-in invoice [:invoice/seller :db/id])
                      :credit-memo (get-in invoice [:invoice/buyer :db/id])
                      :debit-memo  (get-in invoice [:invoice/seller :db/id]))
        lines (->> (d/q '[:find [?l ...]
                          :in $ ?inv
                          :where [?l :invoice-line/invoice ?inv]]
                        db invoice-eid)
                   (map #(d/pull db '[*] %))
                   (sort-by :invoice-line/sequence))
        ;; Build per-line postings
        line-postings
        (mapv (fn [line]
                (let [account-type (:invoice-line/gl-account-type line)
                      override     (get-in line [:invoice-line/account :db/id])
                      amount       (:invoice-line/amount line)
                      account      (resolve-gl-account
                                    db {:override-account override
                                        :account-type account-type
                                        :entity entity-eid})
                      direction    (debit-credit-for invoice-type account-type)
                      signed-amt   (if (= direction :debit)
                                     amount
                                     (.negate ^java.math.BigDecimal amount))]
                  (cond-> {:posting/transaction tx-tempid
                           :posting/account account
                           :posting/amount signed-amt
                           :posting/partner partner-eid}
                    entity-eid (assoc :posting/entity entity-eid))))
              lines)
        ;; Build the contra (AR/AP) posting
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
        contra-posting (cond-> {:posting/transaction tx-tempid
                                :posting/account contra-account
                                :posting/amount contra-amount
                                :posting/partner partner-eid}
                         entity-eid (assoc :posting/entity entity-eid))]
    (conj line-postings contra-posting)))

;; ============================================================================
;; Orchestrator
;; ============================================================================

(defn post-to-ledger!
  "Post the invoice to the GL. In one tx:
     1. Verifies the invoice is in :ready (or :draft if skip-ready
        is allowed).
     2. Builds a :transaction + per-line :posting entries.
     3. Sets :invoice/transaction, :invoice/posted-at, and transitions
        :invoice/status → :sent via kontor.status-machine.

   Returns a map of {:tx-report ... :transaction-eid ... :invoice-eid ...}.

   Opts:
     :posted-at         instant (default now)
     :changed-by-uid    optional ref to :create/uid
     :reason            optional rationale (recorded in status-history)"
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
        current-status (sm/current-status db invoice-eid :invoice/status)
        _ (when-not (#{:invoice.status/draft :invoice.status/ready} current-status)
            (throw (ex-info "Invoice not in postable state"
                            {:invoice-eid    invoice-eid
                             :current-status current-status
                             :postable-from  #{:invoice.status/draft :invoice.status/ready}})))
        posted-at (or (:posted-at opts) (java.util.Date.))
        tx-tempid "tx-1"
        transaction-tx {:db/id tx-tempid
                        :transaction/effective-date posted-at
                        :transaction/state :draft}
        postings (build-postings db invoice-eid tx-tempid)
        all-tx-data (cons transaction-tx postings)
        tx-report (d/transact conn all-tx-data)
        transaction-eid (get-in tx-report [:tempids tx-tempid])]
    ;; Set the :invoice/transaction ref + :invoice/posted-at
    (d/transact conn [{:db/id invoice-eid
                       :invoice/transaction transaction-eid
                       :invoice/posted-at posted-at}])
    ;; Transition status :draft|:ready → :sent
    (sm/record-status-change! conn
                              (cond-> {:entity invoice-eid
                                       :entity-type :invoice
                                       :facet :invoice/status
                                       :to :invoice.status/sent
                                       :changed-at posted-at
                                       :origin-transaction transaction-eid}
                                (:changed-by-uid opts) (assoc :changed-by-uid (:changed-by-uid opts))
                                (:reason opts) (assoc :reason (:reason opts))))
    {:tx-report tx-report
     :transaction-eid transaction-eid
     :invoice-eid invoice-eid}))

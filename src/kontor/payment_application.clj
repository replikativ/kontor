(ns kontor.payment-application
  "Partial-payment primitive — ADR-043.

   A `:payment-application` row records that some amount of a cash-
   receipt transaction `:payment` was applied to a specific
   `:invoice`. The row is bitemporal via datahike tx-time: the
   `:applied-at` field gives the wall-clock instant of the
   application, and the tx-time gives the (immutable) recording
   instant.

   Three primitives close the procurement-era scope-cut at
   `kontor.reconciliation:38-47`:

     - apply-payment!         create one application row + (when
                              the invoice's status transitions)
                              the matching :status-history row.
     - reverse-application!   write a negated row with :reversal-of
                              pointing at the original. Datahike's
                              tx-time gives free allocation replay.
     - open-amount-of         (invoice gross − Σ applied) per
                              invoice at any `:as-of-tx`/`:as-of-
                              valid`. Aging reads this.

   Lives in the kernel (not `kontor-collections`) because revrec
   (Stage M) and subscription (Stage N) consume the same primitive.

   ## Bitemporal interop (experimental, see kontor.bitemporal)

   The `:payment-application/applied-at` attribute IS the per-entity
   valid-time for an application — when the cash hit the books in
   the world. Classical `:as-of-valid` reads filter on it.

   `apply-payment!` / `reverse-application!` additionally accept
   `:vt-from` (and optional `:vt-to`) opts: when present the entire
   tx is stamped with kontor.bitemporal's `:tx/valid-from`. This
   propagates the same valid-time onto the status-history record(s)
   the tx writes, so `(kbt/value-at db invoice :invoice/status vt)`
   reproduces what `:invoice/status` was at any historical valid-
   time. Defaults to `:applied-at` when `:vt-from` is omitted, so
   existing callers keep their semantics."
  (:require [datahike.api :as d]
            [kontor.bitemporal :as kbt]
            [kontor.status-machine :as sm]))

;; ============================================================================
;; Resolution
;; ============================================================================

(defn- resolve-invoice [db spec]
  (cond
    (nil? spec)    nil
    (string? spec) (d/q '[:find ?e .
                          :in $ ?xid
                          :where [?e :invoice/external-id ?xid]]
                        db spec)
    :else          spec))

(defn- pull-invoice-min [db invoice-eid]
  (d/pull db
          [:db/id :invoice/external-id :invoice/status :invoice/currency
           {:invoice/seller [:db/id]}
           {:invoice/buyer [:db/id]}]
          invoice-eid))

;; ============================================================================
;; Queries
;; ============================================================================

(defn applications-of
  "Pulled :payment-application rows for an invoice, oldest-first by
   :applied-at. Honors `:as-of-valid` opt (default now). Net = sum of
   :amount including any reversals."
  ([db invoice-spec] (applications-of db invoice-spec nil))
  ([db invoice-spec {:keys [as-of-valid]}]
   (when-let [invoice-eid (resolve-invoice db invoice-spec)]
     (let [as-of-valid (or as-of-valid (java.util.Date.))
           cutoff-millis (.getTime ^java.util.Date as-of-valid)
           rows (d/q '[:find [?app ...]
                       :in $ ?inv ?cutoff-ms
                       :where
                       [?app :payment-application/invoice ?inv]
                       [?app :payment-application/applied-at ?when]
                       [(.getTime ^java.util.Date ?when) ?when-ms]
                       [(<= ?when-ms ?cutoff-ms)]]
                     db invoice-eid cutoff-millis)]
       (->> rows
            (map #(d/pull db '[*] %))
            (sort-by :payment-application/applied-at)
            vec)))))

(defn applied-amount-of-invoice
  "Sum of :payment-application/amount for an invoice (positive +
   negative reversals net out). Returns BigDecimal."
  ([db invoice-spec] (applied-amount-of-invoice db invoice-spec nil))
  ([db invoice-spec {:keys [as-of-valid]}]
   (let [apps (applications-of db invoice-spec {:as-of-valid as-of-valid})]
     (reduce (fn [^java.math.BigDecimal acc app]
               (.add acc ^java.math.BigDecimal
                     (or (:payment-application/amount app) 0M)))
             0M
             apps))))

(defn open-amount-of-invoice
  "Bitemporal open-amount = (invoice gross − applied). The invoice
   gross is `:invoice/total-gross` if present, else the sum of
   `:invoice-line/amount` across lines.

   Returns BigDecimal."
  ([db invoice-spec] (open-amount-of-invoice db invoice-spec nil))
  ([db invoice-spec {:keys [as-of-valid] :as opts}]
   (when-let [invoice-eid (resolve-invoice db invoice-spec)]
     (let [gross (or (:invoice/total-gross
                      (d/pull db [:invoice/total-gross] invoice-eid))
                     (or (d/q '[:find (sum ?amt) .
                                :with ?l
                                :in $ ?inv
                                :where
                                [?l :invoice-line/invoice ?inv]
                                [?l :invoice-line/amount ?amt]]
                              db invoice-eid)
                         0M))
           applied (applied-amount-of-invoice db invoice-eid opts)]
       (.subtract ^java.math.BigDecimal gross
                  ^java.math.BigDecimal applied)))))

;; NOTE: `unapplied-cash-balance` deferred to Stage L companion. The
;; kernel doesn't model "cash received from partner X" cleanly without
;; a sum-of-postings-against-cash-account query, which couples to
;; chart-of-accounts. The collections companion re-derives it from
;; postings on AR/cash accounts when it lands.

;; ============================================================================
;; Bitemporal reads — invoice status at any valid-time
;; ============================================================================

(defn invoice-status-at
  "Resolve `:invoice/status` at valid-time `cutoff` using kontor.
   bitemporal. Requires the kontor.bitemporal schema to be installed
   AND the txs that drove the status change to have been stamped
   with `:tx/valid-from` (which `apply-payment!` /
   `reverse-application!` do automatically when their `:applied-at`
   is set or `:vt-from` is passed).

   Composes with `(d/as-of db tx)` for full bitemporal time travel:

       (invoice-status-at (d/as-of db tx-id)
                          (resolve-invoice db inv)
                          #inst \"2026-04-01\")

   Returns the keyword status or nil if no assertion applies."
  [db invoice-spec cutoff]
  (when-let [eid (resolve-invoice db invoice-spec)]
    (kbt/value-at db eid :invoice/status cutoff)))

;; ============================================================================
;; Transactors
;; ============================================================================

(defn- next-status-for-application
  "Decide the invoice's new `:invoice/status` given the current
   status, the open-amount after this application, and whether
   reversals were involved."
  [current-status open-after-amount]
  (cond
    ;; Open balance went to zero or below → :paid.
    (and (or (= current-status :sent)
             (= current-status :partially-paid))
         (<= (.signum ^java.math.BigDecimal open-after-amount) 0))
    :paid

    ;; First positive application on a :sent invoice with remaining
    ;; balance → :partially-paid.
    (and (= current-status :sent)
         (pos? (.signum ^java.math.BigDecimal open-after-amount)))
    :partially-paid

    ;; Additional application on :partially-paid → stays :partially-paid
    ;; (the self-loop is legal).
    (= current-status :partially-paid) :partially-paid

    ;; Reversal that re-opens a previously :paid invoice → :sent
    ;; (caller should set this explicitly via reverse-application!).
    :else nil))

(defn apply-payment!
  "Record a `:payment-application` row + drive the invoice's
   `:invoice/status` facet accordingly.

   Required opts:
     :payment        ref or eid to the cash-receipt :transaction.
     :invoice        ref/eid/external-id of the invoice.
     :amount         BigDecimal. Positive reduces the invoice's open
                     balance. Negative is a reversal — but prefer
                     `reverse-application!` which sets :reversal-of.
     :commodity      ref to :commodity. Must match invoice currency.
     :applied-by-uid ref to :create/uid.

   Optional opts:
     :applied-at        instant, default now (also drives the tx's
                        :tx/valid-from when :vt-from is omitted).
     :strategy          :fifo | :customer-instruction | :proportional
                        | :cherry-pick | :reversal (default
                        :cherry-pick).
     :reason            keyword.
     :reason-note       string.
     :supporting-doc    ref to :audit-doc.
     :vt-from           instant — override tx-level valid-time
                        (kontor.bitemporal). Default: `:applied-at`.
     :vt-to             instant — optional upper bound for the tx's
                        valid-time interval. Default: open-ended.

   Returns the tx-report."
  [conn {:keys [payment invoice amount commodity applied-by-uid
                applied-at strategy reason reason-note supporting-doc
                vt-from vt-to]
         :or {strategy :cherry-pick}}]
  (when-not payment        (throw (ex-info ":payment required" {})))
  (when-not invoice        (throw (ex-info ":invoice required" {})))
  (when-not amount         (throw (ex-info ":amount required" {})))
  (when-not commodity      (throw (ex-info ":commodity required" {})))
  (when-not applied-by-uid (throw (ex-info ":applied-by-uid required" {})))
  (let [db (d/db conn)
        invoice-eid (resolve-invoice db invoice)
        _ (when-not invoice-eid
            (throw (ex-info "Invoice not found" {:spec invoice})))
        inv (pull-invoice-min db invoice-eid)
        current-status (:invoice/status inv)
        applied-at (or applied-at (java.util.Date.))
        app-tempid "pay-app-1"
        app-row (cond-> {:db/id app-tempid
                         :payment-application/payment payment
                         :payment-application/invoice invoice-eid
                         :payment-application/amount amount
                         :payment-application/commodity commodity
                         :payment-application/applied-at applied-at
                         :payment-application/applied-by-uid applied-by-uid
                         :payment-application/strategy strategy}
                  reason         (assoc :payment-application/reason reason)
                  reason-note    (assoc :payment-application/reason-note reason-note)
                  supporting-doc (assoc :payment-application/supporting-doc supporting-doc))
        ;; Compute open-after by simulating: pre-existing applied +
        ;; this amount; pull gross from invoice (or sum of lines).
        already-applied (applied-amount-of-invoice db invoice-eid nil)
        gross (or (:invoice/total-gross
                   (d/pull db [:invoice/total-gross] invoice-eid))
                  (or (d/q '[:find (sum ?amt) .
                             :with ?l
                             :in $ ?inv
                             :where
                             [?l :invoice-line/invoice ?inv]
                             [?l :invoice-line/amount ?amt]]
                           db invoice-eid)
                      0M))
        open-after (.subtract ^java.math.BigDecimal gross
                              ^java.math.BigDecimal
                              (.add ^java.math.BigDecimal already-applied
                                    ^java.math.BigDecimal amount))
        next-status (next-status-for-application current-status open-after)
        ;; Status change is optional — only fire when next-status differs
        ;; from current AND a legal transition exists.
        status-tx (when (and next-status
                             (not= next-status current-status)
                             (sm/legal-transition? db :invoice
                                                   :invoice/status
                                                   current-status next-status))
                    (sm/record-status-change-tx-data
                     db
                     (cond-> {:entity invoice-eid
                              :entity-type :invoice
                              :facet :invoice/status
                              :from current-status
                              :to next-status
                              :changed-at applied-at
                              :changed-by-uid applied-by-uid}
                       reason      (assoc :reason reason)
                       reason-note (assoc :reason-note reason-note))))
        ;; Self-loop on :partially-paid is legal but not interesting to
        ;; write a status-history row for; the application row itself
        ;; carries the audit info. So even if next-status = :partially-
        ;; paid = current-status, we DON'T write a history row.
        all-tx (cond-> [app-row]
                 status-tx (into status-tx))
        ;; Bitemporal stamp on the tx: vt-from defaults to applied-at
        ;; so the application's tx is consistent with the world-time
        ;; of the cash receipt.
        effective-vt-from (or vt-from applied-at)
        final-tx (cond
                   (and effective-vt-from vt-to)
                   (kbt/with-vt all-tx effective-vt-from vt-to)
                   effective-vt-from
                   (kbt/with-vt all-tx effective-vt-from)
                   :else all-tx)]
    (d/transact conn final-tx)))

(defn reverse-application!
  "Replayable reversal of a prior `:payment-application`. Writes a
   new row with `:reversal-of` pointing at the original and the
   negated amount.

   The invoice's status may move backwards (`:paid → :sent` if the
   reversal reopens the full balance, or `:paid → :partially-paid`
   if a previous partial remains).

   Required opts:
     :application-eid  ref/eid of the prior row.
     :applied-by-uid   ref to :create/uid (whoever's reversing).

   Optional opts:
     :applied-at      instant, default now.
     :reason          keyword.
     :reason-note     string.
     :supporting-doc  ref to :audit-doc.
     :vt-from         instant — override tx-level valid-time
                      (kontor.bitemporal). Default: `:applied-at`.
     :vt-to           instant — optional upper bound."
  [conn {:keys [application-eid applied-by-uid applied-at reason
                reason-note supporting-doc vt-from vt-to]}]
  (when-not application-eid (throw (ex-info ":application-eid required" {})))
  (when-not applied-by-uid  (throw (ex-info ":applied-by-uid required" {})))
  (let [db (d/db conn)
        original (d/pull db '[* {:payment-application/invoice [:db/id :invoice/status]
                                 :payment-application/payment [:db/id]
                                 :payment-application/commodity [:db/id]}]
                         application-eid)
        _ (when-not (:db/id original)
            (throw (ex-info "Application not found" {:eid application-eid})))
        invoice-eid (get-in original [:payment-application/invoice :db/id])
        current-status (get-in original [:payment-application/invoice :invoice/status])
        original-amount (:payment-application/amount original)
        negated (.negate ^java.math.BigDecimal original-amount)
        applied-at (or applied-at (java.util.Date.))
        rev-tempid "pay-app-rev-1"
        rev-row (cond-> {:db/id rev-tempid
                         :payment-application/payment
                         (get-in original [:payment-application/payment :db/id])
                         :payment-application/invoice invoice-eid
                         :payment-application/amount negated
                         :payment-application/commodity
                         (get-in original [:payment-application/commodity :db/id])
                         :payment-application/applied-at applied-at
                         :payment-application/applied-by-uid applied-by-uid
                         :payment-application/strategy :reversal
                         :payment-application/reversal-of application-eid}
                  reason         (assoc :payment-application/reason reason)
                  reason-note    (assoc :payment-application/reason-note reason-note)
                  supporting-doc (assoc :payment-application/supporting-doc supporting-doc))
        ;; Compute new open-after: prior-net + negated.
        prior-applied (applied-amount-of-invoice db invoice-eid nil)
        new-applied (.add ^java.math.BigDecimal prior-applied
                          ^java.math.BigDecimal negated)
        gross (or (:invoice/total-gross
                   (d/pull db [:invoice/total-gross] invoice-eid))
                  (or (d/q '[:find (sum ?amt) .
                             :with ?l
                             :in $ ?inv
                             :where
                             [?l :invoice-line/invoice ?inv]
                             [?l :invoice-line/amount ?amt]]
                           db invoice-eid)
                      0M))
        open-after (.subtract ^java.math.BigDecimal gross
                              ^java.math.BigDecimal new-applied)
        next-status (cond
                      ;; Reversal of the final application: :paid →
                      ;; :sent (if open-after = full gross) or
                      ;; :partially-paid (if a partial remains).
                      (and (= current-status :paid)
                           (pos? (.signum ^java.math.BigDecimal open-after)))
                      (if (zero? (.compareTo ^java.math.BigDecimal new-applied 0M))
                        :sent
                        :partially-paid)

                      ;; Reversal of a :partially-paid invoice's
                      ;; LAST remaining partial → :sent (back to
                      ;; fully-open). P0-2 fix.
                      (and (= current-status :partially-paid)
                           (zero? (.compareTo ^java.math.BigDecimal
                                              new-applied 0M)))
                      :sent

                      ;; Reversal of a partial when more partials remain:
                      ;; stays :partially-paid.
                      (= current-status :partially-paid) :partially-paid

                      :else nil)
        status-tx (when (and next-status
                             (not= next-status current-status)
                             (sm/legal-transition? db :invoice
                                                   :invoice/status
                                                   current-status next-status))
                    (sm/record-status-change-tx-data
                     db
                     (cond-> {:entity invoice-eid
                              :entity-type :invoice
                              :facet :invoice/status
                              :from current-status
                              :to next-status
                              :changed-at applied-at
                              :changed-by-uid applied-by-uid}
                       reason      (assoc :reason reason)
                       reason-note (assoc :reason-note reason-note))))
        all-tx (cond-> [rev-row]
                 status-tx (into status-tx))
        effective-vt-from (or vt-from applied-at)
        final-tx (cond
                   (and effective-vt-from vt-to)
                   (kbt/with-vt all-tx effective-vt-from vt-to)
                   effective-vt-from
                   (kbt/with-vt all-tx effective-vt-from)
                   :else all-tx)]
    (d/transact conn final-tx)))

;; ============================================================================
;; FIFO bulk allocation
;; ============================================================================

(defn- open-invoices-for-partner
  "List `:sent` and `:partially-paid` invoices for a partner ordered
   by due-date oldest-first (FIFO). Returns vec of
   {:invoice-eid :open-amount :due-date}."
  [db partner-eid {:keys [as-of-valid exclude-disputed?]}]
  (let [as-of-valid (or as-of-valid (java.util.Date.))
        eids (d/q '[:find [?i ...]
                    :in $ ?p
                    :where
                    [?i :invoice/buyer ?p]
                    (or [?i :invoice/status :sent]
                        [?i :invoice/status :partially-paid])]
                  db partner-eid)]
    (->> eids
         (map (fn [eid]
                (let [open (open-amount-of-invoice db eid {:as-of-valid as-of-valid})
                      pulled (d/pull db
                                     [{:invoice/transaction
                                       [:transaction/due-date]}]
                                     eid)
                      due (or (get-in pulled
                                      [:invoice/transaction
                                       :transaction/due-date])
                              (java.util.Date. 0))]
                  {:invoice-eid eid
                   :open-amount open
                   :due-date    due})))
         (filter #(pos? (.signum ^java.math.BigDecimal (:open-amount %))))
         (sort-by :due-date)
         vec)))

(defn allocate-fifo!
  "Apply a payment across N open invoices for a partner, oldest-first.

   Required opts:
     :payment         ref/eid to the cash-receipt :transaction.
     :partner         ref/eid to :partner (the payer; matches
                      :invoice/buyer).
     :total-amount    BigDecimal — the cash to allocate.
     :commodity       ref to :commodity.
     :applied-by-uid  ref to :create/uid.

   Optional opts:
     :applied-at         instant, default now.
     :exclude-disputed?  filter open invoices with open :dispute rows
                         (Stage L companion will define :dispute;
                         kernel ignores until the companion is
                         installed).
     :reason             keyword.

   Returns a vec of `{:invoice-eid :allocated}` records describing
   the allocation. Tx-data is composed and transacted atomically.
   Underflow (cash &gt; sum of open amounts) leaves residual cash in
   the payment — `(unapplied-cash-balance ...)` reports it.

   Overflow (cash &lt; needed) means the oldest invoices get full
   allocation and the next one gets a partial; remaining invoices
   stay :sent/:partially-paid."
  [conn {:keys [payment partner total-amount commodity applied-by-uid
                applied-at exclude-disputed? reason]}]
  (when-not payment        (throw (ex-info ":payment required" {})))
  (when-not partner        (throw (ex-info ":partner required" {})))
  (when-not total-amount   (throw (ex-info ":total-amount required" {})))
  (when-not commodity      (throw (ex-info ":commodity required" {})))
  (when-not applied-by-uid (throw (ex-info ":applied-by-uid required" {})))
  (let [db (d/db conn)
        applied-at (or applied-at (java.util.Date.))
        openers (open-invoices-for-partner db partner
                                            {:exclude-disputed? exclude-disputed?
                                             :as-of-valid applied-at})
        allocations (loop [remaining total-amount
                           candidates openers
                           out []]
                      (if (or (empty? candidates)
                              (zero? (.signum ^java.math.BigDecimal remaining)))
                        out
                        (let [{:keys [invoice-eid open-amount]} (first candidates)
                              alloc (if (>= (.compareTo ^java.math.BigDecimal open-amount
                                                        ^java.math.BigDecimal remaining) 0)
                                      remaining
                                      open-amount)]
                          (recur (.subtract ^java.math.BigDecimal remaining
                                            ^java.math.BigDecimal alloc)
                                 (rest candidates)
                                 (conj out {:invoice-eid invoice-eid
                                            :allocated alloc})))))]
    (doseq [{:keys [invoice-eid allocated]} allocations]
      (apply-payment! conn
                      {:payment payment
                       :invoice invoice-eid
                       :amount allocated
                       :commodity commodity
                       :applied-by-uid applied-by-uid
                       :applied-at applied-at
                       :strategy :fifo
                       :reason (or reason :fifo-allocation)}))
    allocations))

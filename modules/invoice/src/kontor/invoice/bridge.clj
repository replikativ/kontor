(ns kontor.invoice.bridge
  "Public surface of the `kontor-invoice` companion — ADR-036.

   IMPORTANT: namespace is `kontor.invoice.bridge`, NOT
   `kontor.invoice`. The kernel ships its own `kontor.invoice`
   (`src/kontor/invoice.clj`) with create!/send!/mark-paid!/cancel!
   for non-order-aware flows. This bridge namespace adds the order-
   aware machinery on top: order→invoice construction, status-
   machine wrappers using ADR-034 transitions, and the posting
   trigger.

   Resolution, pulls, status-transition wrappers, order→invoice
   bridge (`make-invoice-from-order!`), and the posting trigger
   (which delegates to kontor.invoice.posting/post-to-ledger!).

   The order→invoice bridge logic:
     1. Pull the :order header + items + adjustments.
     2. Create an :invoice with :invoice/order back-ref.
     3. For each :order-item, create one :invoice-line + one
        :order-item-billing junction row.
     4. For each :order-adjustment, create a derived :invoice-line
        with :invoice-line/parent-line pointing at the parent
        product line."
  (:require [datahike.api :as d]
            [kontor.invoice.posting :as posting]
            [kontor.status-machine :as sm]))

;; ============================================================================
;; Resolution
;; ============================================================================

(defn by-external-id
  "Resolve an invoice eid by :invoice/external-id."
  [db external-id]
  (d/q '[:find ?e .
         :in $ ?xid
         :where [?e :invoice/external-id ?xid]]
       db external-id))

(defn resolve-invoice
  "Coerce `spec` to an invoice eid (string → lookup, eid → identity)."
  [db spec]
  (cond
    (nil? spec)    nil
    (string? spec) (by-external-id db spec)
    :else          spec))

;; ============================================================================
;; Pulls
;; ============================================================================

(defn pull-invoice
  [db spec]
  (when-let [eid (resolve-invoice db spec)]
    (d/pull db
            '[* {:invoice/order   [:order/external-id :order/type]
                 :invoice/entity  [:entity/code :entity/name]
                 :invoice/buyer   [:partner/external-id :partner/name]
                 :invoice/seller  [:partner/external-id :partner/name]
                 :invoice/transaction [:db/id]}]
            eid)))

(defn lines-of
  "Pulled :invoice-line rows ordered by :invoice-line/sequence."
  [db spec]
  (when-let [eid (resolve-invoice db spec)]
    (->> (d/q '[:find [?l ...]
                :in $ ?i
                :where [?l :invoice-line/invoice ?i]]
              db eid)
         (map #(d/pull db '[*] %))
         (sort-by :invoice-line/sequence)
         vec)))

;; ============================================================================
;; Totals
;; ============================================================================

(defn total-of
  "Sum of :invoice-line/amount across all lines. Returns bigdec."
  [db spec]
  (when-let [eid (resolve-invoice db spec)]
    (reduce (fn [acc {:invoice-line/keys [amount]}]
              (.add ^java.math.BigDecimal acc
                    ^java.math.BigDecimal (or amount 0M)))
            0M
            (lines-of db eid))))

(defn partial-billed-quantity
  "Sum of :order-item-billing/quantity across all junctions for the
   given :order-item entity-id. Returns bigdec.

   Used by the bridge to subtract already-invoiced quantity when
   constructing a second invoice for the same order."
  [db order-item-eid]
  (or (d/q '[:find (sum ?q) .
             :with ?b
             :in $ ?oi
             :where
             [?b :order-item-billing/order-item ?oi]
             [?b :order-item-billing/quantity ?q]]
           db order-item-eid)
      0M))

;; ============================================================================
;; Status transitions
;; ============================================================================

(defn make-ready!
  "Finalize an invoice (:draft → :ready). Locks edits."
  ([conn invoice] (make-ready! conn invoice nil))
  ([conn invoice opts]
   (let [eid (resolve-invoice (d/db conn) invoice)]
     (sm/record-status-change! conn
                               (merge {:entity eid
                                       :entity-type :invoice
                                       :facet :invoice/status
                                       :to :ready}
                                      opts)))))

(defn post-to-ledger!
  "Post the invoice to the GL. Delegates to kontor.invoice.posting/
   post-to-ledger!. See that fn for details."
  ([conn invoice] (post-to-ledger! conn invoice nil))
  ([conn invoice opts]
   (posting/post-to-ledger! conn invoice opts)))

(defn mark-paid!
  "Transition :sent → :paid. Typically called by reconciliation
   when the bank-line settles."
  ([conn invoice] (mark-paid! conn invoice nil))
  ([conn invoice opts]
   (let [eid (resolve-invoice (d/db conn) invoice)]
     (sm/record-status-change! conn
                               (merge {:entity eid
                                       :entity-type :invoice
                                       :facet :invoice/status
                                       :to :paid}
                                      opts)))))

(defn cancel!
  "Transition any non-cancelled state → :cancelled. If already :sent,
   the consumer is responsible for creating a reversal :transaction
   per ADR-007 sealing."
  ([conn invoice] (cancel! conn invoice nil))
  ([conn invoice opts]
   (let [eid (resolve-invoice (d/db conn) invoice)]
     (sm/record-status-change! conn
                               (merge {:entity eid
                                       :entity-type :invoice
                                       :facet :invoice/status
                                       :to :cancelled}
                                      opts)))))

;; ============================================================================
;; Order → invoice bridge
;; ============================================================================

(defn- gl-account-type-for-item-line
  "Dispatch on (invoice-type, :order-item/category) → :invoice-line/
   gl-account-type. ADR-042: polymorphism for procurement invoices.

   Sales / credit-memo: always :sales-revenue (category ignored).
   Purchase / debit-memo:
     :direct   → :inventory       (goods for resale, raw materials)
     :indirect → :purchase-expense (office supplies)
     :services → :purchase-expense (consulting, legal)
     :asset    → :asset-acquisition (CapEx)
     nil       → :purchase-expense (legacy default)"
  [invoice-type order-item]
  (case invoice-type
    (:sales :credit-memo)
    :sales-revenue

    (:purchase :debit-memo)
    (case (:order-item/category order-item)
      :direct   :inventory
      :indirect :purchase-expense
      :services :purchase-expense
      :asset    :asset-acquisition
      :purchase-expense)

    :sales-revenue))

(defn- gl-account-type-for-adjustment-line
  "Dispatch on (invoice-type, :order-adjustment/type) → :invoice-line/
   gl-account-type. ADR-042: polymorphism for procurement adjustments."
  [invoice-type adj-type]
  (case [invoice-type adj-type]
    [:sales :tax]            :sales-tax-payable
    [:sales :discount]       :discount-given
    [:sales :shipping]       :shipping-income
    [:credit-memo :tax]      :sales-tax-payable
    [:credit-memo :discount] :discount-given
    [:credit-memo :shipping] :shipping-income
    [:purchase :tax]         :purchase-tax-recoverable
    [:purchase :discount]    :purchase-discount
    [:purchase :shipping]    :shipping-expense
    [:debit-memo :tax]       :purchase-tax-recoverable
    [:debit-memo :discount]  :purchase-discount
    [:debit-memo :shipping]  :shipping-expense
    ;; fallback per invoice-type
    (case invoice-type
      (:sales :credit-memo)    :sales-revenue
      (:purchase :debit-memo)  :purchase-expense
      :sales-revenue)))

(defn- make-invoice-line-from-order-item
  "Build an :invoice-line + :order-item-billing junction for one
   order-item. The line's quantity is the remaining un-billed
   quantity (subtract :order-item-billing/quantity totals).

   `invoice-type` (ADR-042) drives the GL routing via
   `gl-account-type-for-item-line`."
  [db invoice-type order-item invoice-tempid sequence line-tempid]
  (let [order-item-eid (:db/id order-item)
        ordered-qty (:order-item/quantity order-item)
        cancelled-qty (or (:order-item/cancel-quantity order-item) 0M)
        already-billed (partial-billed-quantity db order-item-eid)
        bill-qty (.subtract ^java.math.BigDecimal
                            (.subtract ^java.math.BigDecimal ordered-qty cancelled-qty)
                            ^java.math.BigDecimal already-billed)
        gl-type (gl-account-type-for-item-line invoice-type order-item)]
    (cond
      ;; Nothing left to bill (already fully invoiced) — emit a zero-
      ;; quantity line. Caller `make-invoice-from-order!` filters.
      (zero? (.signum ^java.math.BigDecimal bill-qty))
      [{:db/id line-tempid
        :invoice-line/invoice invoice-tempid
        :invoice-line/sequence sequence
        :invoice-line/order-item order-item-eid
        :invoice-line/name (or (:order-item/description order-item)
                               (:order-item/product-id order-item))
        :invoice-line/quantity 0M
        :invoice-line/unit-price (:order-item/unit-price order-item)
        :invoice-line/amount 0M
        :invoice-line/gl-account-type gl-type}
       nil]

      ;; Negative remaining quantity = cancellation increased past
      ;; already-billed. Refuse — caller must issue a credit memo
      ;; rather than try to net it on the next invoice.
      (neg? (.signum ^java.math.BigDecimal bill-qty))
      (throw (ex-info "Refusing to invoice negative quantity"
                      {:type :invoice/over-billed-or-over-cancelled
                       :order-item order-item-eid
                       :ordered-qty ordered-qty
                       :cancelled-qty cancelled-qty
                       :already-billed already-billed
                       :computed-bill-qty bill-qty
                       :remediation "Issue a credit memo for the
                                     already-billed quantity instead
                                     of attempting to net on a new
                                     invoice."}))

      :else
      (let [unit-price (:order-item/unit-price order-item)
            line-amount (.multiply ^java.math.BigDecimal bill-qty
                                   ^java.math.BigDecimal unit-price)
            override-acct (when-let [a (:order-item/override-gl-account order-item)]
                            (:db/id a))]
        [(cond-> {:db/id line-tempid
                  :invoice-line/invoice invoice-tempid
                  :invoice-line/sequence sequence
                  :invoice-line/order-item order-item-eid
                  :invoice-line/name (or (:order-item/description order-item)
                                         (:order-item/product-id order-item))
                  :invoice-line/quantity bill-qty
                  :invoice-line/unit-price unit-price
                  :invoice-line/amount line-amount
                  :invoice-line/gl-account-type gl-type}
           override-acct (assoc :invoice-line/account override-acct))
         {:order-item-billing/order-item order-item-eid
          :order-item-billing/invoice-line line-tempid
          :order-item-billing/quantity bill-qty}]))))

(defn- make-invoice-line-from-adjustment
  "Build an :invoice-line for one :order-adjustment. The line is
   parented to the parent product line if the adjustment is
   line-scoped, otherwise is header-level on the invoice.

   ADR-042: `invoice-type` drives the GL routing via
   `gl-account-type-for-adjustment-line` (e.g. :purchase + :tax →
   :purchase-tax-recoverable, mirror of :sales + :tax →
   :sales-tax-payable)."
  [invoice-type adjustment invoice-tempid sequence line-tempid parent-line-tempid]
  (let [adj-type (:order-adjustment/type adjustment)
        gl-type (gl-account-type-for-adjustment-line invoice-type adj-type)
        amount (:order-adjustment/amount adjustment)
        override-acct (when-let [a (:order-adjustment/override-gl-account adjustment)]
                        (:db/id a))
        tax-auth-party (when-let [p (:order-adjustment/tax-auth-party adjustment)]
                         (:db/id p))]
    (cond-> {:db/id line-tempid
             :invoice-line/invoice invoice-tempid
             :invoice-line/sequence sequence
             :invoice-line/order-adjustment (:db/id adjustment)
             :invoice-line/name (str (name adj-type))
             :invoice-line/quantity 1M
             :invoice-line/unit-price amount
             :invoice-line/amount amount
             :invoice-line/gl-account-type gl-type}
      parent-line-tempid    (assoc :invoice-line/parent-line parent-line-tempid)
      override-acct         (assoc :invoice-line/account override-acct)
      tax-auth-party        (assoc :invoice-line/tax-auth-party tax-auth-party)
      (:order-adjustment/tax-auth-geo-id adjustment)
      (assoc :invoice-line/tax-auth-geo-id (:order-adjustment/tax-auth-geo-id adjustment)))))

(defn make-invoice-from-order!
  "Build an :invoice + :invoice-line rows + :order-item-billing
   junctions from the given :order. Returns the tx-report.

   Args:
     conn       — datahike connection
     order-spec — :order eid or :order/external-id string
     opts       — map with:
       :external-id  — :invoice/external-id (required)
       :issue-date   — :invoice/issue-date (default now)
       :type         — :invoice/type, default :sales
       :entity       — :invoice/entity (overrides :order/entity if set)
       :ship-group   — optional :ship-group ref; when set, the invoice
                       is only for items in that ship group (the OFBiz
                       invoicePerShipment pattern). Currently a
                       placeholder — full ship-group filtering is a
                       follow-up.

   The :invoice/status starts at :draft. Caller invokes
   `(post-to-ledger! conn invoice)` to transition to :sent."
  [conn order-spec {:keys [external-id issue-date type entity ship-group] :as _opts}]
  (let [db (d/db conn)
        order-eid (cond
                    (string? order-spec)
                    (d/q '[:find ?e . :in $ ?xid
                           :where [?e :order/external-id ?xid]]
                         db order-spec)
                    :else order-spec)
        _ (when-not order-eid
            (throw (ex-info "Order not found" {:spec order-spec})))
        _ (when-not external-id
            (throw (ex-info "make-invoice-from-order! requires :external-id"
                            {:order order-spec})))
        order (d/pull db
                      '[* {:order/currency [:commodity/symbol]
                           :order/bill-from-partner [:db/id]
                           :order/bill-to-partner [:db/id]
                           :order/entity [:db/id]}]
                      order-eid)
        items (->> (d/q '[:find [?i ...]
                          :in $ ?o
                          :where [?i :order-item/order ?o]]
                        db order-eid)
                   (map #(d/pull db '[*] %))
                   (sort-by :order-item/seq-id))
        adjustments (->> (d/q '[:find [?a ...]
                                :in $ ?o
                                :where [?a :order-adjustment/order ?o]]
                              db order-eid)
                         (map #(d/pull db '[*] %)))
        invoice-tempid "inv-1"
        ;; ADR-042: default :invoice/type from :order/type (was hardcoded :sales).
        invoice-type (or type
                         (case (:order/type order)
                           :purchase :purchase
                           :sales))
        invoice-entity-eid (or entity (get-in order [:order/entity :db/id]))
        ;; Build product lines + billing junctions (ADR-042: invoice-type
        ;; threads through to gl-account-type dispatch).
        item-results (map-indexed
                      (fn [idx item]
                        (make-invoice-line-from-order-item db invoice-type item
                                                           invoice-tempid
                                                           (inc idx)
                                                           (str "line-" (inc idx))))
                      items)
        item-line-rows (map first item-results)
        item-billings  (remove nil? (map second item-results))
        ;; P0-4 fix: also set the :invoice/lines forward-ref (kernel
        ;; maintains both forward + back-ref; consumers of
        ;; kontor.invoice/send! walk :invoice/lines).
        ;; Map :order-item eid → its line tempid (for line-scoped
        ;; adjustments)
        order-item-eid->tempid (into {}
                                    (map (fn [item line]
                                           [(:db/id item) (:db/id line)])
                                         items item-line-rows))
        ;; Build adjustment lines (ADR-042: invoice-type threads through)
        adj-line-rows (map-indexed
                       (fn [idx adj]
                         (let [scope-eid (get-in adj [:order-adjustment/scope :db/id])
                               parent-tempid (get order-item-eid->tempid scope-eid)
                               adj-seq (+ (count items) (inc idx))]
                           (make-invoice-line-from-adjustment
                            invoice-type adj invoice-tempid adj-seq
                            (str "adj-" (inc idx))
                            parent-tempid)))
                       adjustments)
        all-line-tempids (into (mapv :db/id item-line-rows)
                               (map :db/id adj-line-rows))
        invoice-row (cond-> {:db/id invoice-tempid
                             :invoice/external-id external-id
                             :invoice/issue-date (or issue-date (java.util.Date.))
                             :invoice/type invoice-type
                             :invoice/status :draft
                             :invoice/order order-eid
                             :invoice/seller (get-in order [:order/bill-from-partner :db/id])
                             :invoice/buyer  (get-in order [:order/bill-to-partner :db/id])
                             :invoice/currency (get-in order [:order/currency :commodity/symbol])
                             :invoice/lines all-line-tempids}
                      invoice-entity-eid (assoc :invoice/entity invoice-entity-eid)
                      ship-group         (assoc :invoice/invoice-per-shipment-of ship-group))
        all-tx-data (concat [invoice-row]
                            item-line-rows
                            item-billings
                            adj-line-rows)]
    (d/transact conn (vec all-tx-data))))

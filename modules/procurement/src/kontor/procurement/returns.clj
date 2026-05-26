(ns kontor.procurement.returns
  "Return helpers — ADR-042.

   `:kontor.return/type :customer | :vendor` is the role-inverted
   discriminator (OFBiz ReturnHeader.returnHeaderTypeId pattern).
   95% same codepath; the inversion is the from-party / to-party
   refs.

   State machine: :requested → :accepted → :received → :completed
   (plus :cancelled and :rejected escapes).

   Credit memos are kernel `:kontor.invoice/type :credit-memo` (for
   :customer returns) or `:debit-memo` (for :vendor returns), linked
   via `:return-item-billing` junction.

   Namespace named `returns` (not `return`) to avoid the reserved
   word."
  (:require [datahike.api :as d]
            [kontor.status-machine :as sm]
            [kontor.validation :as validation]))

;; ============================================================================
;; Resolution
;; ============================================================================

(defn by-external-id
  [db external-id]
  (d/q '[:find ?e .
         :in $ ?xid
         :where [?e :kontor.return/external-id ?xid]]
       db external-id))

(defn resolve-return
  [db spec]
  (cond
    (nil? spec)    nil
    (string? spec) (by-external-id db spec)
    :else          spec))

(defn pull-return
  [db spec]
  (when-let [eid (resolve-return db spec)]
    (d/pull db
            '[* {:kontor.return/from-party [:kontor.partner/external-id :kontor.partner/name]
                 :kontor.return/to-party [:kontor.partner/external-id :kontor.partner/name]
                 :kontor.return/order [:kontor.order/external-id :kontor.order/type]
                 :kontor.return/entity [:kontor.entity/code]
                 :kontor.return/supporting-doc [:kontor.audit-doc/code :kontor.audit-doc/type]}]
            eid)))

(defn items-of
  [db spec]
  (when-let [eid (resolve-return db spec)]
    (->> (d/q '[:find [?i ...]
                :in $ ?r
                :where [?i :kontor.return-item/return ?r]]
              db eid)
         (map #(d/pull db '[* {:kontor.return-item/order-item [:db/id
                                                         :kontor.sales.order-item/seq-id
                                                         :kontor.sales.order-item/product-id
                                                         :kontor.procurement.order-item/category]
                                :kontor.return-item/response [*]}] %))
         (sort-by :kontor.return-item/seq-id)
         vec)))

;; ============================================================================
;; Transactors
;; ============================================================================

(declare make-return-tx-data make-credit-memo-from-return-tx-data)

(defn make-return-tx-data
  "Pure tx-data builder for `make-return!` (ADR-068)."
  [_db {:keys [external-id type from-party to-party order entity
               destination-facility-id supplier-rma entry-date notes
               supporting-doc items tempid]
        :or {tempid "return-1"}}]
  (when-not external-id (throw (ex-info ":external-id required" {})))
  (when-not type        (throw (ex-info ":type (:customer or :vendor) required" {})))
  (when-not from-party  (throw (ex-info ":from-party required" {})))
  (when-not to-party    (throw (ex-info ":to-party required" {})))
  (when-not (seq items) (throw (ex-info "non-empty :items required" {})))
  (let [return-row (cond-> {:db/id tempid
                            :kontor.return/external-id external-id
                            :kontor.return/type type
                            :kontor.return/status :requested
                            :kontor.return/from-party from-party
                            :kontor.return/to-party to-party
                            :kontor.return/order order
                            :kontor.return/entry-date (or entry-date (java.util.Date.))}
                     entity                  (assoc :kontor.return/entity entity)
                     destination-facility-id (assoc :kontor.return/destination-facility-id destination-facility-id)
                     supplier-rma            (assoc :kontor.return/supplier-rma supplier-rma)
                     notes                   (assoc :kontor.return/notes notes)
                     supporting-doc          (assoc :kontor.return/supporting-doc supporting-doc))
        item-rows (mapv (fn [idx item]
                          (cond-> {:kontor.return-item/return tempid
                                   :kontor.return-item/order-item (:order-item item)
                                   :kontor.return-item/seq-id (or (:seq-id item)
                                                            (format "%05d" (inc idx)))
                                   :kontor.return-item/return-quantity (:return-quantity item)
                                   :kontor.return-item/status :requested}
                            (:product-id item)
                            (assoc :kontor.return-item/product-id (:product-id item))

                            (:return-price item)
                            (assoc :kontor.return-item/return-price (:return-price item))

                            (:reason item)
                            (assoc :kontor.return-item/reason (:reason item))

                            (:return-type item)
                            (assoc :kontor.return-item/return-type (:return-type item))

                            (:expected-disposition item)
                            (assoc :kontor.return-item/expected-disposition
                                   (:expected-disposition item))))
                        (range)
                        items)]
    (vec (cons return-row item-rows))))

(defn make-return!
  "Create a `:return` + `:return-item` rows in `:requested` state.
   Routes through the gate (ADR-068).

   Required:
     :external-id, :type (:customer | :vendor), :from-party, :to-party,
     :order, :items (vec of return-item maps).

   Each item map requires :order-item, :return-quantity, and
   optionally :seq-id (auto-assigned if absent), :reason,
   :return-type, :expected-disposition, :product-id, :return-price.

   Optional return-level: :entity, :destination-facility-id,
   :supplier-rma, :entry-date, :notes, :supporting-doc.

   The pure tx-data builder is `make-return-tx-data`."
  [conn opts]
  (validation/transact-with-validation
   conn (make-return-tx-data (d/db conn) opts)))

(defn accept-return!
  "Transition :requested → :accepted (RMA approved)."
  ([conn return] (accept-return! conn return nil))
  ([conn return opts]
   (let [eid (resolve-return (d/db conn) return)]
     (sm/record-status-change! conn
                               (merge {:entity eid
                                       :entity-type :return
                                       :facet :kontor.return/status
                                       :to :accepted}
                                      opts)))))

(defn reject-return!
  "Transition :requested → :rejected (RMA denied)."
  ([conn return] (reject-return! conn return nil))
  ([conn return opts]
   (let [eid (resolve-return (d/db conn) return)]
     (sm/record-status-change! conn
                               (merge {:entity eid
                                       :entity-type :return
                                       :facet :kontor.return/status
                                       :to :rejected}
                                      opts)))))

(defn cancel-return!
  "Cancel a :requested or :accepted return."
  ([conn return] (cancel-return! conn return nil))
  ([conn return opts]
   (let [eid (resolve-return (d/db conn) return)]
     (sm/record-status-change! conn
                               (merge {:entity eid
                                       :entity-type :return
                                       :facet :kontor.return/status
                                       :to :cancelled}
                                      opts)))))

(defn receive-return!
  "Transition :accepted → :received. Caller must update
   :kontor.return-item/received-quantity per line via separate tx if it
   differs from :return-quantity."
  ([conn return] (receive-return! conn return nil))
  ([conn return opts]
   (let [eid (resolve-return (d/db conn) return)]
     (sm/record-status-change! conn
                               (merge {:entity eid
                                       :entity-type :return
                                       :facet :kontor.return/status
                                       :to :received}
                                      opts)))))

(defn complete-return!
  "Transition :received → :completed (refund / replacement / credit
   memo issued)."
  ([conn return] (complete-return! conn return nil))
  ([conn return opts]
   (let [eid (resolve-return (d/db conn) return)]
     (sm/record-status-change! conn
                               (merge {:entity eid
                                       :entity-type :return
                                       :facet :kontor.return/status
                                       :to :completed}
                                      opts)))))

;; ============================================================================
;; Credit-memo bridge
;; ============================================================================

(defn- credit-memo-invoice-type
  "For :customer returns → :credit-memo. For :vendor returns →
   :debit-memo."
  [return-type]
  (case return-type
    :customer :credit-memo
    :vendor   :debit-memo))

(defn make-credit-memo-from-return-tx-data
  "Pure tx-data builder for `make-credit-memo-from-return!` (ADR-068)."
  [db return-spec {:keys [external-id issue-date]}]
  (when-not external-id
    (throw (ex-info ":external-id required" {})))
  (let [return-eid (resolve-return db return-spec)
        _ (when-not return-eid
            (throw (ex-info "Return not found" {:spec return-spec})))
        return (d/pull db
                       '[* {:kontor.return/from-party [:db/id]
                            :kontor.return/to-party [:db/id]
                            :kontor.return/entity [:db/id]
                            :kontor.return/order [:db/id {:kontor.order/currency [:kontor.commodity/symbol]}]}]
                       return-eid)
        return-type (:kontor.return/type return)
        invoice-type (credit-memo-invoice-type return-type)
        ;; The polarity is symmetric: :kontor.return/to-party is the issuer
        ;; of the credit (= invoice :seller), :kontor.return/from-party is
        ;; the recipient (= invoice :buyer). For :customer returns
        ;; we (the org) issue credit to the customer. For :vendor
        ;; returns the supplier issues credit to us (the kontor
        ;; convention follows Odoo / Coupa, not SAP's buyer-issued
        ;; debit-memo pattern).
        seller-eid (get-in return [:kontor.return/to-party :db/id])
        buyer-eid  (get-in return [:kontor.return/from-party :db/id])
        currency (get-in return [:kontor.return/order :kontor.order/currency :kontor.commodity/symbol])
        items (items-of db return-eid)
        invoice-tempid "credit-memo-1"
        line-tempids (mapv #(str "credit-line-" (inc %)) (range (count items)))
        line-rows (mapv (fn [item line-tempid idx]
                          (let [qty (or (:kontor.return-item/received-quantity item)
                                        (:kontor.return-item/return-quantity item))
                                price (or (:kontor.return-item/return-price item) 0M)
                                amount (.multiply ^java.math.BigDecimal qty
                                                  ^java.math.BigDecimal price)
                                ;; GL routing: credit-memo lines reverse a
                                ;; prior sale, so they hit :sales-revenue
                                ;; (the polarity flip in default-direction-
                                ;; for makes the posting Dr revenue, Cr AR).
                                ;; Debit-memo lines reverse a prior purchase
                                ;; via :kontor.procurement.order-item/category dispatch — same
                                ;; account-type the original purchase line
                                ;; would have used. Direct-material RTVs
                                ;; that need explicit inventory adjustment
                                ;; should compose plan-stock-move :direction
                                ;; :out separately and let the debit-memo
                                ;; clear AP; the bridge here only models
                                ;; the AP side.
                                category (:kontor.procurement.order-item/category
                                           (:kontor.return-item/order-item item))
                                gl-type (case invoice-type
                                          :credit-memo :sales-revenue
                                          :debit-memo
                                          (case category
                                            :direct   :inventory
                                            :indirect :purchase-expense
                                            :services :purchase-expense
                                            :asset    :asset-acquisition
                                            :purchase-expense))]
                            {:db/id line-tempid
                             :kontor.invoice-line/invoice invoice-tempid
                             :kontor.invoice-line/sequence (inc idx)
                             :kontor.invoice-line/order-item (:db/id
                                                       (:kontor.return-item/order-item item))
                             :kontor.invoice-line/name (or (:kontor.return-item/product-id item)
                                                     "Return")
                             :kontor.invoice-line/quantity qty
                             :kontor.invoice-line/unit-price price
                             :kontor.invoice-line/amount amount
                             :kontor.invoice-line/gl-account-type gl-type}))
                        items line-tempids (range))
        billing-rows (mapv (fn [item line-tempid]
                             {:kontor.return-item-billing/return-item (:db/id item)
                              :kontor.return-item-billing/invoice-line line-tempid
                              :kontor.return-item-billing/quantity
                              (or (:kontor.return-item/received-quantity item)
                                  (:kontor.return-item/return-quantity item))
                              :kontor.return-item-billing/amount
                              (.multiply
                               ^java.math.BigDecimal
                               (or (:kontor.return-item/received-quantity item)
                                   (:kontor.return-item/return-quantity item) 0M)
                               ^java.math.BigDecimal
                               (or (:kontor.return-item/return-price item) 0M))})
                           items line-tempids)
        invoice-row (cond-> {:db/id invoice-tempid
                             :kontor.invoice/external-id external-id
                             :kontor.invoice/type invoice-type
                             :kontor.invoice/status :draft
                             :kontor.invoice/issue-date (or issue-date (java.util.Date.))
                             :kontor.invoice/seller seller-eid
                             :kontor.invoice/buyer buyer-eid
                             :kontor.invoice/order (get-in return [:kontor.return/order :db/id])
                             :kontor.invoice/lines (mapv :db/id line-rows)}
                      currency (assoc :kontor.invoice/currency currency)
                      (get-in return [:kontor.return/entity :db/id])
                      (assoc :kontor.invoice/entity (get-in return [:kontor.return/entity :db/id])))]
    (vec (concat [invoice-row] line-rows billing-rows))))

(defn make-credit-memo-from-return!
  "Build a kernel :invoice (with :kontor.invoice/type :credit-memo or
   :debit-memo per return-type) + :invoice-line rows + :return-item-
   billing junctions atomically. Routes through the gate (ADR-068).
   Returns the tx-report.

   Required opts:
     :external-id — for the new invoice
     :issue-date  — optional, default now

   Reads each :return-item's :return-quantity (or :received-quantity
   if set) and :return-price; produces one :invoice-line per
   return-item. Junction rows link :return-item ↔ :invoice-line for
   the credit-memo audit trail.

   The pure tx-data builder is `make-credit-memo-from-return-tx-data`."
  [conn return-spec opts]
  (validation/transact-with-validation
   conn (make-credit-memo-from-return-tx-data (d/db conn) return-spec opts)))

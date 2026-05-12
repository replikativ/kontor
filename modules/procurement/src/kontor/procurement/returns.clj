(ns kontor.procurement.returns
  "Return helpers — ADR-042.

   `:return/type :customer | :vendor` is the role-inverted
   discriminator (OFBiz ReturnHeader.returnHeaderTypeId pattern).
   95% same codepath; the inversion is the from-party / to-party
   refs.

   State machine: :requested → :accepted → :received → :completed
   (plus :cancelled and :rejected escapes).

   Credit memos are kernel `:invoice/type :credit-memo` (for
   :customer returns) or `:debit-memo` (for :vendor returns), linked
   via `:return-item-billing` junction.

   Namespace named `returns` (not `return`) to avoid the reserved
   word."
  (:require [datahike.api :as d]
            [kontor.status-machine :as sm]))

;; ============================================================================
;; Resolution
;; ============================================================================

(defn by-external-id
  [db external-id]
  (d/q '[:find ?e .
         :in $ ?xid
         :where [?e :return/external-id ?xid]]
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
            '[* {:return/from-party [:partner/external-id :partner/name]
                 :return/to-party [:partner/external-id :partner/name]
                 :return/order [:order/external-id :order/type]
                 :return/entity [:entity/code]
                 :return/supporting-doc [:audit-doc/code :audit-doc/type]}]
            eid)))

(defn items-of
  [db spec]
  (when-let [eid (resolve-return db spec)]
    (->> (d/q '[:find [?i ...]
                :in $ ?r
                :where [?i :return-item/return ?r]]
              db eid)
         (map #(d/pull db '[* {:return-item/order-item [:db/id
                                                         :order-item/seq-id
                                                         :order-item/product-id
                                                         :order-item/category]
                                :return-item/response [*]}] %))
         (sort-by :return-item/seq-id)
         vec)))

;; ============================================================================
;; Transactors
;; ============================================================================

(defn make-return!
  "Create a `:return` + `:return-item` rows in `:requested` state.

   Required:
     :external-id, :type (:customer | :vendor), :from-party, :to-party,
     :order, :items (vec of return-item maps).

   Each item map requires :order-item, :return-quantity, and
   optionally :seq-id (auto-assigned if absent), :reason,
   :return-type, :expected-disposition, :product-id, :return-price.

   Optional return-level: :entity, :destination-facility-id,
   :supplier-rma, :entry-date, :notes, :supporting-doc."
  [conn {:keys [external-id type from-party to-party order entity
                destination-facility-id supplier-rma entry-date notes
                supporting-doc items]}]
  (when-not external-id (throw (ex-info ":external-id required" {})))
  (when-not type        (throw (ex-info ":type (:customer or :vendor) required" {})))
  (when-not from-party  (throw (ex-info ":from-party required" {})))
  (when-not to-party    (throw (ex-info ":to-party required" {})))
  (when-not (seq items) (throw (ex-info "non-empty :items required" {})))
  (let [return-tempid "return-1"
        return-row (cond-> {:db/id return-tempid
                            :return/external-id external-id
                            :return/type type
                            :return/status :requested
                            :return/from-party from-party
                            :return/to-party to-party
                            :return/order order
                            :return/entry-date (or entry-date (java.util.Date.))}
                     entity                  (assoc :return/entity entity)
                     destination-facility-id (assoc :return/destination-facility-id destination-facility-id)
                     supplier-rma            (assoc :return/supplier-rma supplier-rma)
                     notes                   (assoc :return/notes notes)
                     supporting-doc          (assoc :return/supporting-doc supporting-doc))
        item-rows (mapv (fn [idx item]
                          (cond-> {:return-item/return return-tempid
                                   :return-item/order-item (:order-item item)
                                   :return-item/seq-id (or (:seq-id item)
                                                            (format "%05d" (inc idx)))
                                   :return-item/return-quantity (:return-quantity item)
                                   :return-item/status :requested}
                            (:product-id item)
                            (assoc :return-item/product-id (:product-id item))

                            (:return-price item)
                            (assoc :return-item/return-price (:return-price item))

                            (:reason item)
                            (assoc :return-item/reason (:reason item))

                            (:return-type item)
                            (assoc :return-item/return-type (:return-type item))

                            (:expected-disposition item)
                            (assoc :return-item/expected-disposition
                                   (:expected-disposition item))))
                        (range)
                        items)]
    (d/transact conn (vec (cons return-row item-rows)))))

(defn accept-return!
  "Transition :requested → :accepted (RMA approved)."
  ([conn return] (accept-return! conn return nil))
  ([conn return opts]
   (let [eid (resolve-return (d/db conn) return)]
     (sm/record-status-change! conn
                               (merge {:entity eid
                                       :entity-type :return
                                       :facet :return/status
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
                                       :facet :return/status
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
                                       :facet :return/status
                                       :to :cancelled}
                                      opts)))))

(defn receive-return!
  "Transition :accepted → :received. Caller must update
   :return-item/received-quantity per line via separate tx if it
   differs from :return-quantity."
  ([conn return] (receive-return! conn return nil))
  ([conn return opts]
   (let [eid (resolve-return (d/db conn) return)]
     (sm/record-status-change! conn
                               (merge {:entity eid
                                       :entity-type :return
                                       :facet :return/status
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
                                       :facet :return/status
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

(defn make-credit-memo-from-return!
  "Build a kernel :invoice (with :invoice/type :credit-memo or
   :debit-memo per return-type) + :invoice-line rows + :return-item-
   billing junctions atomically. Returns the tx-report.

   Required opts:
     :external-id — for the new invoice
     :issue-date  — optional, default now

   Reads each :return-item's :return-quantity (or :received-quantity
   if set) and :return-price; produces one :invoice-line per
   return-item. Junction rows link :return-item ↔ :invoice-line for
   the credit-memo audit trail."
  [conn return-spec {:keys [external-id issue-date]}]
  (when-not external-id
    (throw (ex-info ":external-id required" {})))
  (let [db (d/db conn)
        return-eid (resolve-return db return-spec)
        _ (when-not return-eid
            (throw (ex-info "Return not found" {:spec return-spec})))
        return (d/pull db
                       '[* {:return/from-party [:db/id]
                            :return/to-party [:db/id]
                            :return/entity [:db/id]
                            :return/order [:db/id {:order/currency [:commodity/symbol]}]}]
                       return-eid)
        return-type (:return/type return)
        invoice-type (credit-memo-invoice-type return-type)
        ;; The polarity is symmetric: :return/to-party is the issuer
        ;; of the credit (= invoice :seller), :return/from-party is
        ;; the recipient (= invoice :buyer). For :customer returns
        ;; we (the org) issue credit to the customer. For :vendor
        ;; returns the supplier issues credit to us (the kontor
        ;; convention follows Odoo / Coupa, not SAP's buyer-issued
        ;; debit-memo pattern).
        seller-eid (get-in return [:return/to-party :db/id])
        buyer-eid  (get-in return [:return/from-party :db/id])
        currency (get-in return [:return/order :order/currency :commodity/symbol])
        items (items-of db return-eid)
        invoice-tempid "credit-memo-1"
        line-tempids (mapv #(str "credit-line-" (inc %)) (range (count items)))
        line-rows (mapv (fn [item line-tempid idx]
                          (let [qty (or (:return-item/received-quantity item)
                                        (:return-item/return-quantity item))
                                price (or (:return-item/return-price item) 0M)
                                amount (.multiply ^java.math.BigDecimal qty
                                                  ^java.math.BigDecimal price)
                                ;; GL routing: credit-memo lines reverse a
                                ;; prior sale, so they hit :sales-revenue
                                ;; (the polarity flip in default-direction-
                                ;; for makes the posting Dr revenue, Cr AR).
                                ;; Debit-memo lines reverse a prior purchase
                                ;; via :order-item/category dispatch — same
                                ;; account-type the original purchase line
                                ;; would have used. Direct-material RTVs
                                ;; that need explicit inventory adjustment
                                ;; should compose plan-stock-move :direction
                                ;; :out separately and let the debit-memo
                                ;; clear AP; the bridge here only models
                                ;; the AP side.
                                category (:order-item/category
                                           (:return-item/order-item item))
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
                             :invoice-line/invoice invoice-tempid
                             :invoice-line/sequence (inc idx)
                             :invoice-line/order-item (:db/id
                                                       (:return-item/order-item item))
                             :invoice-line/name (or (:return-item/product-id item)
                                                     "Return")
                             :invoice-line/quantity qty
                             :invoice-line/unit-price price
                             :invoice-line/amount amount
                             :invoice-line/gl-account-type gl-type}))
                        items line-tempids (range))
        billing-rows (mapv (fn [item line-tempid]
                             {:return-item-billing/return-item (:db/id item)
                              :return-item-billing/invoice-line line-tempid
                              :return-item-billing/quantity
                              (or (:return-item/received-quantity item)
                                  (:return-item/return-quantity item))
                              :return-item-billing/amount
                              (.multiply
                               ^java.math.BigDecimal
                               (or (:return-item/received-quantity item)
                                   (:return-item/return-quantity item) 0M)
                               ^java.math.BigDecimal
                               (or (:return-item/return-price item) 0M))})
                           items line-tempids)
        invoice-row (cond-> {:db/id invoice-tempid
                             :invoice/external-id external-id
                             :invoice/type invoice-type
                             :invoice/status :draft
                             :invoice/issue-date (or issue-date (java.util.Date.))
                             :invoice/seller seller-eid
                             :invoice/buyer buyer-eid
                             :invoice/order (get-in return [:return/order :db/id])
                             :invoice/lines (mapv :db/id line-rows)}
                      currency (assoc :invoice/currency currency)
                      (get-in return [:return/entity :db/id])
                      (assoc :invoice/entity (get-in return [:return/entity :db/id])))]
    (d/transact conn (vec (concat [invoice-row] line-rows billing-rows)))))

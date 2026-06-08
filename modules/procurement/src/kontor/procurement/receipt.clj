(ns kontor.procurement.receipt
  "Receipt helpers — ADR-042.

   A `:receipt` is the physical-goods-received anchor for 3-way
   match. Each receipt belongs to a PO (`:kontor.receipt/order`) and has
   N `:receipt-item` rows, one per PO line received. Each item has
   a `:quantity-accepted` + `:quantity-rejected` split with reason.

   The state machine: nil → :pending → :accepted | :rejected. Post-
   inspection rejection (:accepted → :rejected) is also legal for
   quality-issue-found-later.

   Inventory integration via `post-receipt-with-inventory!` composes
   the :pending → :accepted status transition + N
   kontor.posting/plan-stock-move :direction :in invocations (one per
   receipt-item, Dr inventory / Cr gr-ir-clearing) into ONE atomic
   tx. The kernel's GR/IR clearing is the cross-side anchor: the
   invoice posting later Dr's GR-IR / Cr's AP at the same amount
   per-(PO-line, commodity), making the residual a queryable
   invariant per ADR-042."
  (:require [clojure.walk :as walk]
            [datahike.api :as d]
            [kontor.bitemporal :as kbt]
            [kontor.posting :as posting]
            [kontor.workflow.status-machine :as sm]
            [kontor.validation :as validation]
            [kontor.provider.valuation :as valuation]))

;; ============================================================================
;; Resolution
;; ============================================================================

(defn by-external-id
  [db external-id]
  (d/q '[:find ?e .
         :in $ ?xid
         :where [?e :kontor.receipt/external-id ?xid]]
       db external-id))

(defn resolve-receipt
  [db spec]
  (cond
    (nil? spec)    nil
    (string? spec) (by-external-id db spec)
    :else          spec))

(defn pull-receipt
  "Pull a receipt with items + order + ship-group."
  [db spec]
  (when-let [eid (resolve-receipt db spec)]
    (d/pull db
            '[* {:kontor.receipt/order [:kontor.order/external-id :kontor.order/type]
                 :kontor.receipt/ship-group [:kontor.ship-group/seq-id]
                 :kontor.receipt/carrier-partner [:kontor.partner/external-id :kontor.partner/name]
                 :kontor.receipt/packing-slip-ref [:kontor.audit-doc/code :kontor.audit-doc/type]}]
            eid)))

(defn items-of
  "Pulled :receipt-item rows for a receipt."
  [db spec]
  (when-let [eid (resolve-receipt db spec)]
    (->> (d/q '[:find [?ri ...]
                :in $ ?r
                :where [?ri :kontor.receipt-item/receipt ?r]]
              db eid)
         (map #(d/pull db '[* {:kontor.receipt-item/order-item [:kontor.sales.order-item/seq-id
                                                          :kontor.sales.order-item/product-id]}] %))
         vec)))

;; ============================================================================
;; Transactors
;; ============================================================================

(declare make-receipt-tx-data post-receipt-with-inventory-tx-data)

(defn make-receipt-tx-data
  "Pure tx-data builder for `make-receipt!` (ADR-068)."
  [_db {:keys [external-id order items ship-group received-at
               received-by-uid facility-id carrier-partner
               tracking-number packing-slip-ref notes tempid]
        :or {tempid "receipt-1"}}]
  (when-not external-id  (throw (ex-info ":external-id required" {})))
  (when-not order        (throw (ex-info ":order required" {})))
  (when-not (seq items)  (throw (ex-info "non-empty :items required" {})))
  (let [receipt-row (cond-> {:db/id tempid
                             :kontor.receipt/external-id external-id
                             :kontor.receipt/order order
                             :kontor.receipt/status :pending
                             :kontor.receipt/received-at (or received-at (java.util.Date.))}
                      ship-group       (assoc :kontor.receipt/ship-group ship-group)
                      received-by-uid  (assoc :kontor.receipt/received-by-uid received-by-uid)
                      facility-id      (assoc :kontor.receipt/facility-id facility-id)
                      carrier-partner  (assoc :kontor.receipt/carrier-partner carrier-partner)
                      tracking-number  (assoc :kontor.receipt/tracking-number tracking-number)
                      packing-slip-ref (assoc :kontor.receipt/packing-slip-ref packing-slip-ref)
                      notes            (assoc :kontor.receipt/notes notes))
        item-rows (mapv (fn [{:keys [order-item product-id quantity-accepted
                                     quantity-rejected rejection-reason
                                     lot unit-cost]}]
                          (cond-> {:kontor.receipt-item/receipt tempid
                                   :kontor.receipt-item/order-item order-item
                                   :kontor.receipt-item/quantity-accepted quantity-accepted}
                            product-id        (assoc :kontor.receipt-item/product-id product-id)
                            quantity-rejected (assoc :kontor.receipt-item/quantity-rejected quantity-rejected)
                            rejection-reason  (assoc :kontor.receipt-item/rejection-reason rejection-reason)
                            lot               (assoc :kontor.receipt-item/lot lot)
                            unit-cost         (assoc :kontor.receipt-item/unit-cost unit-cost)))
                        items)]
    (vec (cons receipt-row item-rows))))

(defn make-receipt!
  "Create a `:receipt` + `:receipt-item` rows in `:pending` state.
   Routes through the gate (ADR-068).

   Required:
     :external-id, :order, :received-at, :items (vec of receipt-item maps).

   Each item map requires :order-item, :quantity-accepted (and may
   include :quantity-rejected + :rejection-reason + :lot + :unit-cost).

   Optional receipt-level: :ship-group, :received-by-uid, :facility-id,
   :carrier-partner, :tracking-number, :packing-slip-ref, :notes.

   The pure tx-data builder is `make-receipt-tx-data`."
  [conn opts]
  (validation/transact-with-validation
   conn (make-receipt-tx-data (d/db conn) opts)))

(defn accept-receipt!
  "Transition :pending → :accepted (inspection pass)."
  ([conn receipt] (accept-receipt! conn receipt nil))
  ([conn receipt opts]
   (let [eid (resolve-receipt (d/db conn) receipt)]
     (sm/record-status-change! conn
                               (merge {:entity eid
                                       :entity-type :receipt
                                       :facet :kontor.receipt/status
                                       :to :accepted}
                                      opts)))))

(defn reject-receipt!
  "Transition :pending → :rejected or :accepted → :rejected (post-
   inspection quality issue)."
  ([conn receipt] (reject-receipt! conn receipt nil))
  ([conn receipt opts]
   (let [eid (resolve-receipt (d/db conn) receipt)]
     (sm/record-status-change! conn
                               (merge {:entity eid
                                       :entity-type :receipt
                                       :facet :kontor.receipt/status
                                       :to :rejected}
                                      opts)))))

;; ============================================================================
;; Inventory posting: receipt → GL via plan-stock-move (ADR-042)
;; ============================================================================

(defn- offset-tempids
  "Walk tx-data shifting every negative integer by `offset` (subtraction,
   so result is more negative). Used to compose N plan-stock-move
   invocations in one tx without tempid collisions — each call uses
   the same tempid space (-1, -200, -300..), and the offset makes
   them disjoint."
  [tx-data offset]
  (walk/postwalk
   (fn [x]
     (if (and (integer? x) (neg? x) (not (instance? java.math.BigInteger x)))
       (- x offset)
       x))
   tx-data))

(defn- default-account-fn
  "Three-tier GL resolution via :gl-account-default — entity-scoped
   first, then tenant-wide. Throws when neither tier has a row for the
   stock-move-role keyword (:inventory / :gr-ir-clearing /
   :price-variance / :cogs)."
  [db entity-eid]
  (fn [_move role]
    (or (when entity-eid
          (d/q '[:find ?a .
                 :in $ ?at ?e
                 :where
                 [?d :kontor.gl-account-default/account-type ?at]
                 [?d :kontor.gl-account-default/entity ?e]
                 [?d :kontor.gl-account-default/account ?a]]
               db role entity-eid))
        (d/q '[:find ?a .
               :in $ ?at
               :where
               [?d :kontor.gl-account-default/account-type ?at]
               [?d :kontor.gl-account-default/account ?a]
               [(missing? $ ?d :kontor.gl-account-default/entity)]]
             db role)
        (throw (ex-info "No :gl-account-default seeded for stock-move role"
                        {:type        :kontor.receipt/missing-gl-default
                         :role        role
                         :entity      entity-eid
                         :remediation "Seed a :gl-account-default row for
                                       :inventory + :gr-ir-clearing (+
                                       :price-variance if using standard-
                                       cost). Or pass a custom :account-fn
                                       to post-receipt-with-inventory!."})))))

(defn post-receipt-with-inventory-tx-data
  "Pure tx-data builder for `post-receipt-with-inventory!` (ADR-068).
   Returns the composed posting + valuation + status-history tx-data
   vector (without `:vt-from`/`:vt-to` wrapping)."
  [db receipt-spec {:keys [provider book journal-ref ledger-ref
                           account-fn effective-date changed-by-uid
                           reason]}]
  (when-not provider    (throw (ex-info ":provider required" {:type :kontor.receipt/missing-provider})))
  (when-not journal-ref (throw (ex-info ":journal-ref required" {:type :kontor.receipt/missing-journal})))
  (let [receipt-eid (resolve-receipt db receipt-spec)
        _ (when-not receipt-eid
            (throw (ex-info "Receipt not found" {:spec receipt-spec})))
        receipt (d/pull db
                        '[* {:kontor.receipt/order [:db/id
                                             {:kontor.order/entity [:db/id]
                                              :kontor.order/currency [:db/id :kontor.commodity/symbol]}]}]
                        receipt-eid)
        _ (when-not (= :pending (:kontor.receipt/status receipt))
            (throw (ex-info "Receipt must be :pending to post"
                            {:type :kontor.receipt/not-pending
                             :receipt receipt-eid
                             :status (:kontor.receipt/status receipt)})))
        entity-eid (get-in receipt [:kontor.receipt/order :kontor.order/entity :db/id])
        commodity-eid (get-in receipt [:kontor.receipt/order :kontor.order/currency :db/id])
        _ (when-not commodity-eid
            (throw (ex-info "Receipt's order has no :kontor.order/currency"
                            {:type :kontor.receipt/missing-commodity
                             :receipt receipt-eid})))
        eff-date (or effective-date (java.util.Date.))
        book-eid (or book (valuation/primary db))
        _ (when-not book-eid
            (throw (ex-info "No :valuation-book available"
                            {:type :kontor.receipt/missing-book
                             :remediation "Pass :book explicitly or seed
                                           a :valuation-book row before
                                           posting."})))
        account-fn (or account-fn (default-account-fn db entity-eid))
        items (->> (d/q '[:find [?ri ...]
                          :in $ ?r
                          :where [?ri :kontor.receipt-item/receipt ?r]]
                        db receipt-eid)
                   (map #(d/pull db '[*] %))
                   (sort-by :db/id))
        _ (when (empty? items)
            (throw (ex-info "Receipt has no items" {:receipt receipt-eid})))
        _ (doseq [item items]
            (when-not (:kontor.receipt-item/unit-cost item)
              (throw (ex-info ":kontor.receipt-item/unit-cost required for posting"
                              {:type :kontor.receipt/missing-unit-cost
                               :receipt-item (:db/id item)}))))
        ;; The state-machine middleware requires :kontor.transaction/posted-at
        ;; in the same tx as :kontor.transaction/state :posted. plan-stock-move
        ;; sets state but not posted-at; stamp it here.
        stamp-posted-at (fn [tx-row]
                          (if (and (map? tx-row)
                                   (= :posted (:kontor.transaction/state tx-row)))
                            (assoc tx-row :kontor.transaction/posted-at eff-date)
                            tx-row))
        per-item-tx
        (mapv (fn [idx item]
                (let [move-spec {:direction :in
                                 :book book-eid
                                 :item (:db/id (:kontor.receipt-item/order-item item))
                                 :qty (:kontor.receipt-item/quantity-accepted item)
                                 :unit-cost (:kontor.receipt-item/unit-cost item)
                                 :commodity commodity-eid
                                 :lot (:db/id (:kontor.receipt-item/lot item))
                                 :journal journal-ref
                                 :ledger ledger-ref
                                 :effective-date eff-date
                                 :provider provider
                                 :account-fn account-fn
                                 :transaction-state :posted}
                      raw-tx (posting/plan-stock-move db move-spec)]
                  (mapv stamp-posted-at
                        (offset-tempids raw-tx (* 1000 idx)))))
              (range)
              items)
        all-posting-tx (vec (mapcat identity per-item-tx))
        status-tx (sm/record-status-change-tx-data
                   db
                   (cond-> {:entity receipt-eid
                            :entity-type :receipt
                            :facet :kontor.receipt/status
                            :to :accepted
                            :changed-at eff-date}
                     changed-by-uid (assoc :changed-by-uid changed-by-uid)
                     reason (assoc :reason reason)))]
    (vec (concat all-posting-tx status-tx))))

(defn post-receipt-with-inventory!
  "Atomically transition a :pending receipt → :accepted, write
   :valuation-layer rows, and post Dr inventory / Cr gr-ir-clearing
   per receipt-item via kontor.posting/plan-stock-move :direction :in.
   Routes through the gate (ADR-068).

   The receipt must:
     - be in :pending status,
     - belong to an :order whose :kontor.order/currency resolves to a
       :commodity,
     - have :receipt-item rows with :unit-cost set (the actual cost
       at receipt time; differences from PO unit-price land on
       :price-variance if the CostingProvider is standard-cost).

   Required opts:
     :provider     CostingProvider impl (FIFO/LIFO/AVG/Standard).
     :journal-ref  ref or lookup-ref to the :journal for these tx's.

   Optional opts:
     :book         valuation-book eid or :kontor.valuation-book/code (default:
                   the primary book via valuation/primary).
     :ledger-ref   posting ledger (default: kernel default).
     :account-fn   override role → account resolver. Default is three-
                   tier :gl-account-default lookup against the order's
                   entity then tenant-wide.
     :effective-date  instant (default now). Also drives the tx's
                      `:tx/valid-from` for kontor.bitemporal when
                      `:vt-from` is omitted.
     :changed-by-uid  ref to :kontor.audit/create-uid for the status-history row.
     :reason          status-history :reason keyword.
     :vt-from         kontor.bitemporal valid-from (default
                      `:effective-date`).
     :vt-to           kontor.bitemporal valid-to (default: open).

   Returns the d/transact report.

   The kernel uses :db/id -1 for the transaction tempid, -200 for the
   layer, and -300- for postings. To compose N moves in one tx, each
   item's tx-data is shifted by (i * 1000).

   The pure tx-data builder is `post-receipt-with-inventory-tx-data`."
  [conn receipt-spec {:keys [effective-date vt-from vt-to] :as opts}]
  (let [eff-date (or effective-date (java.util.Date.))]
    (validation/transact-with-validation
     conn (kbt/with-vt (post-receipt-with-inventory-tx-data
                        (d/db conn) receipt-spec
                        (assoc opts :effective-date eff-date))
                       (or vt-from eff-date)
                       (or vt-to kbt/forever)))))

;; ============================================================================
;; Queries
;; ============================================================================

(defn receipts-of-order
  "All receipts against an order, pulled with items."
  [db order-eid]
  (->> (d/q '[:find [?r ...]
              :in $ ?o
              :where [?r :kontor.receipt/order ?o]]
            db order-eid)
       (map #(pull-receipt db %))
       vec))

(defn quantity-received-of-order-item
  "Net received quantity for `order-item-eid` against the procurement
   3-way invariant `(received − returned) = (invoiced − credited)`.

   Counts:
     + sum of :kontor.receipt-item/quantity-accepted on :receipt rows in
       :accepted status (excludes :pending, :rejected)
     − sum of :kontor.return-item/received-quantity (or :return-quantity if
       not yet received) on :return rows of :type :vendor, where the
       return is past :accepted state (i.e. RMA approved + goods
       physically back at vendor).

   Returns bigdec."
  [db order-item-eid]
  (let [received (or (d/q '[:find (sum ?q) .
                            :with ?ri
                            :in $ ?oi
                            :where
                            [?ri :kontor.receipt-item/order-item ?oi]
                            [?ri :kontor.receipt-item/quantity-accepted ?q]
                            [?ri :kontor.receipt-item/receipt ?r]
                            [?r :kontor.receipt/status :accepted]]
                          db order-item-eid)
                     0M)
        returned (or (d/q '[:find (sum ?q) .
                            :with ?rit
                            :in $ ?oi
                            :where
                            [?rit :kontor.return-item/order-item ?oi]
                            [?rit :kontor.return-item/return ?ret]
                            [?ret :kontor.return/type :vendor]
                            [?ret :kontor.return/status ?st]
                            [(contains? #{:received :completed} ?st)]
                            (or-join [?rit ?q]
                                     [?rit :kontor.return-item/received-quantity ?q]
                                     (and [(missing? $ ?rit :kontor.return-item/received-quantity)]
                                          [?rit :kontor.return-item/return-quantity ?q]))]
                          db order-item-eid)
                     0M)]
    (.subtract ^java.math.BigDecimal received
               ^java.math.BigDecimal returned)))

(defn quantity-rejected-of-order-item
  "Sum of :kontor.receipt-item/quantity-rejected across all receipts for
   `order-item-eid`. Returns bigdec."
  [db order-item-eid]
  (or (d/q '[:find (sum ?q) .
             :with ?ri
             :in $ ?oi
             :where
             [?ri :kontor.receipt-item/order-item ?oi]
             [?ri :kontor.receipt-item/quantity-rejected ?q]]
           db order-item-eid)
      0M))

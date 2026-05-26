(ns kontor.sales
  "Public surface of the `kontor-sales` companion — ADR-035.

   Resolution, status transitions (via kontor.status-machine + the
   seeded :status-transition rows from kontor.sales.schema), pulls /
   queries, and a composable recalc pipeline.

   The companion is intentionally narrow: it owns the order aggregate
   (header, items, ship groups, reservations, adjustments, roles)
   and the state machines for :kontor.order/status + :kontor.sales.order-item/status.
   The order→invoice bridge lives in kontor-invoice (ADR-036)."
  (:require [datahike.api :as d]
            [kontor.status-machine :as sm]
            [kontor.validation :as validation]))

;; ============================================================================
;; Resolution
;; ============================================================================

(defn by-external-id
  "Resolve an order entity-id by `:kontor.order/external-id`."
  [db external-id]
  (d/q '[:find ?e .
         :in $ ?xid
         :where [?e :kontor.order/external-id ?xid]]
       db external-id))

(defn resolve-order
  "Coerce `spec` to an order eid (string → lookup, eid → identity)."
  [db spec]
  (cond
    (nil? spec)    nil
    (string? spec) (by-external-id db spec)
    :else          spec))

;; ============================================================================
;; Pulls / queries
;; ============================================================================

(defn pull-order
  "Pull the order entity by external-id or eid, including all
   first-level refs (currency, partners, entity). Items and ship
   groups are pulled separately via the corresponding helpers."
  [db spec]
  (when-let [eid (resolve-order db spec)]
    (d/pull db
            '[* {:kontor.order/currency [*]
                 :kontor.order/bill-from-partner [:kontor.partner/external-id :kontor.partner/name]
                 :kontor.order/bill-to-partner   [:kontor.partner/external-id :kontor.partner/name]
                 :kontor.order/entity            [:kontor.entity/code :kontor.entity/name]}]
            eid)))

(defn items-of
  "Pulled :order-item rows for the order, sorted by :kontor.sales.order-item/seq-id
   (ascending)."
  [db spec]
  (when-let [oid (resolve-order db spec)]
    (->> (d/q '[:find [?i ...]
                :in $ ?o
                :where [?i :kontor.sales.order-item/order ?o]]
              db oid)
         (map #(d/pull db '[*] %))
         (sort-by :kontor.sales.order-item/seq-id)
         vec)))

(defn ship-groups-of
  "Pulled :ship-group rows for the order, sorted by seq-id."
  [db spec]
  (when-let [oid (resolve-order db spec)]
    (->> (d/q '[:find [?sg ...]
                :in $ ?o
                :where [?sg :kontor.ship-group/order ?o]]
              db oid)
         (map #(d/pull db '[*] %))
         (sort-by :kontor.ship-group/seq-id)
         vec)))

(defn adjustments-of
  "Pulled :order-adjustment rows for the order. Optionally filter by
   level via opts:
     :level :header     — only header-level (scope = order itself)
     :level :line       — only line-level (scope = order-item)
     :level :ship-group — only ship-group-level (scope = ship-group)"
  ([db spec] (adjustments-of db spec nil))
  ([db spec opts]
   (when-let [oid (resolve-order db spec)]
     (let [rows (->> (d/q '[:find [?a ...]
                            :in $ ?o
                            :where [?a :kontor.order-adjustment/order ?o]]
                          db oid)
                     (map #(d/pull db '[*] %))
                     vec)
           level (:level opts)]
       (case level
         :header     (filter #(= (get-in % [:kontor.order-adjustment/scope :db/id]) oid) rows)
         :line       (filter (fn [a]
                               (let [scope-eid (get-in a [:kontor.order-adjustment/scope :db/id])]
                                 (and scope-eid
                                      (some? (:kontor.sales.order-item/seq-id (d/pull db [:kontor.sales.order-item/seq-id] scope-eid))))))
                             rows)
         :ship-group (filter (fn [a]
                               (let [scope-eid (get-in a [:kontor.order-adjustment/scope :db/id])]
                                 (and scope-eid
                                      (some? (:kontor.ship-group/seq-id (d/pull db [:kontor.ship-group/seq-id] scope-eid))))))
                             rows)
         rows)))))

(defn roles-of
  "Pulled :order-role rows for the order."
  [db spec]
  (when-let [oid (resolve-order db spec)]
    (->> (d/q '[:find [?r ...]
                :in $ ?o
                :where [?r :kontor.order-role/order ?o]]
              db oid)
         (map #(d/pull db '[* {:kontor.order-role/partner [:kontor.partner/external-id :kontor.partner/name]}] %))
         vec)))

(defn partner-on-order
  "Resolve the partner eid holding `role-type` on the order, or nil.
   If multiple partners share a role (rare; supported by composite
   identity), returns the first found."
  [db spec role-type]
  (when-let [oid (resolve-order db spec)]
    (d/q '[:find ?p .
           :in $ ?o ?rt
           :where
           [?r :kontor.order-role/order ?o]
           [?r :kontor.order-role/role-type ?rt]
           [?r :kontor.order-role/partner ?p]]
         db oid role-type)))

(defn reservations-of
  "Pulled :inv-reservation rows for the order."
  [db spec]
  (when-let [oid (resolve-order db spec)]
    (->> (d/q '[:find [?r ...]
                :in $ ?o
                :where [?r :kontor.inv-reservation/order ?o]]
              db oid)
         (map #(d/pull db '[*] %))
         vec)))

;; ============================================================================
;; Totals
;; ============================================================================

(defn compute-grand-total
  "Compute the order's grand-total live from items + non-neutral
   adjustments. Does NOT update the denormalized :kontor.order/grand-total
   attribute — that's the recalc pipeline's job. Returns a bigdec."
  [db spec]
  (when-let [oid (resolve-order db spec)]
    (let [items (items-of db oid)
          adjustments (adjustments-of db oid)
          item-total (reduce (fn [acc {:kontor.sales.order-item/keys [quantity unit-price cancel-quantity]
                                       :or {cancel-quantity 0M}}]
                               (let [effective-qty (.subtract ^java.math.BigDecimal (or quantity 0M)
                                                              ^java.math.BigDecimal cancel-quantity)]
                                 (.add ^java.math.BigDecimal acc
                                       (.multiply ^java.math.BigDecimal effective-qty
                                                  ^java.math.BigDecimal (or unit-price 0M)))))
                             0M
                             items)
          adj-total (reduce (fn [acc {:kontor.order-adjustment/keys [amount neutral?]}]
                              (if neutral?
                                acc
                                (.add ^java.math.BigDecimal acc
                                      ^java.math.BigDecimal (or amount 0M))))
                            0M
                            adjustments)]
      (.add ^java.math.BigDecimal item-total ^java.math.BigDecimal adj-total))))

;; ============================================================================
;; Status transitions — wrappers over kontor.status-machine
;; ============================================================================

(defn approve-order!
  "Transition :kontor.order/status :created → :approved. Throws if illegal."
  ([conn order] (approve-order! conn order nil))
  ([conn order opts]
   (let [oid (resolve-order (d/db conn) order)]
     (sm/record-status-change! conn
                               (merge {:entity      oid
                                       :entity-type :order
                                       :facet       :kontor.order/status
                                       :to          :order.status/approved}
                                      opts)))))

(defn hold-order!
  ([conn order] (hold-order! conn order nil))
  ([conn order opts]
   (let [oid (resolve-order (d/db conn) order)]
     (sm/record-status-change! conn
                               (merge {:entity      oid
                                       :entity-type :order
                                       :facet       :kontor.order/status
                                       :to          :order.status/hold}
                                      opts)))))

(defn release-from-hold!
  ([conn order] (release-from-hold! conn order nil))
  ([conn order opts]
   (let [oid (resolve-order (d/db conn) order)]
     (sm/record-status-change! conn
                               (merge {:entity      oid
                                       :entity-type :order
                                       :facet       :kontor.order/status
                                       :to          :order.status/approved}
                                      opts)))))

(defn cancel-order!
  ([conn order] (cancel-order! conn order nil))
  ([conn order opts]
   (let [oid (resolve-order (d/db conn) order)]
     (sm/record-status-change! conn
                               (merge {:entity      oid
                                       :entity-type :order
                                       :facet       :kontor.order/status
                                       :to          :order.status/cancelled}
                                      opts)))))

(defn complete-order!
  ([conn order] (complete-order! conn order nil))
  ([conn order opts]
   (let [oid (resolve-order (d/db conn) order)]
     (sm/record-status-change! conn
                               (merge {:entity      oid
                                       :entity-type :order
                                       :facet       :kontor.order/status
                                       :to          :order.status/completed}
                                      opts)))))

(defn reject-order!
  ([conn order] (reject-order! conn order nil))
  ([conn order opts]
   (let [oid (resolve-order (d/db conn) order)]
     (sm/record-status-change! conn
                               (merge {:entity      oid
                                       :entity-type :order
                                       :facet       :kontor.order/status
                                       :to          :order.status/rejected}
                                      opts)))))

(defn set-item-status!
  "Transition :kontor.sales.order-item/status for the given item eid to `to`."
  ([conn item-eid to] (set-item-status! conn item-eid to nil))
  ([conn item-eid to opts]
   (sm/record-status-change! conn
                             (merge {:entity      item-eid
                                     :entity-type :order-item
                                     :facet       :kontor.sales.order-item/status
                                     :to          to}
                                    opts))))

;; ============================================================================
;; Header promotion (OFBiz checkItemStatus pattern)
;; ============================================================================

(defn check-and-promote-header!
  "Scan all items of the order. If all are in the same terminal state
   (:completed, :cancelled, :rejected), promote the order to the
   matching status. If all are :approved, promote :order to :approved.
   No-op if items are mixed.

   This is the OFBiz `checkItemStatus` pattern, generalized.

   Returns the promoted-to status or nil if no promotion happened."
  [conn order]
  (let [db  (d/db conn)
        oid (resolve-order db order)
        items (items-of db oid)
        statuses (set (map :kontor.sales.order-item/status items))
        current  (sm/current-status db oid :kontor.order/status)
        promote (fn [to]
                  (when (sm/legal-transition? db :order :kontor.order/status current to)
                    (sm/record-status-change! conn
                                              {:entity      oid
                                               :entity-type :order
                                               :facet       :kontor.order/status
                                               :to          to
                                               :reason      :auto-promoted})
                    to))]
    (cond
      (= statuses #{:order-item.status/cancelled}) (promote :order.status/cancelled)
      (= statuses #{:order-item.status/completed}) (promote :order.status/completed)
      (= statuses #{:order-item.status/rejected})  (promote :order.status/rejected)
      ;; All approved or completed → promote to :approved if not
      ;; already (allows :approved promotion when some items finish
      ;; early).
      (every? #{:order-item.status/approved
                :order-item.status/completed} statuses)
      (promote :order.status/approved)
      :else nil)))

;; ============================================================================
;; Recalc pipeline (CompositeOrderProcessor pattern, Sylius-derived)
;; ============================================================================

(defonce ^:private processors
  (atom []))

(defn register-processor!
  "Register a recalc processor. A processor is a fn `(fn [conn order-eid] tx-data)`
   that examines the order and returns tx-data (e.g. retract old
   adjustments + assert new ones). Processors run in registration
   priority order (lower priority first).

   Re-registering with the same `:id` replaces the existing entry.

   This is the Sylius CompositeOrderProcessor pattern. The kernel
   ships no built-in processors; consumers register tax / promotion /
   shipping processors per their domain.

   Required keys: `:id` (any value) and `:proc` (the processor fn).
   Optional: `:priority` (default 0; lower runs first)."
  [{:keys [id priority proc]}]
  (when (or (nil? id) (nil? proc))
    (throw (ex-info "register-processor! requires :id and :proc"
                    {:id id})))
  (swap! processors
         (fn [ps]
           (->> ps
                (remove #(= id (:id %)))
                (cons {:id id :priority (or priority 0) :proc proc})
                (sort-by :priority)
                vec))))

(defn unregister-processor!
  [id]
  (swap! processors (fn [ps] (vec (remove #(= id (:id %)) ps)))))

(defn registered-processors
  "Return the current processor list, sorted by priority."
  []
  @processors)

(defn recalculate-order!
  "Run the recalc pipeline for the order. Each processor returns
   tx-data; processors run sequentially (each sees the prior's
   committed state). Each non-empty tx-data is routed through the
   gate (ADR-068). Returns a vector of tx-reports."
  [conn order]
  (let [oid (resolve-order (d/db conn) order)]
    (mapv (fn [{:keys [proc]}]
            (let [tx-data (proc conn oid)]
              (when (seq tx-data)
                (validation/transact-with-validation conn tx-data))))
          (registered-processors))))

(ns kontor.procurement.requirement
  "Requirement (requisition) helpers — ADR-042.

   A `:requirement` is a demand signal independent of any order. It
   may roll into one or more POs via the `:requirement-commitment`
   junction (many-to-many). The state machine: nil → :proposed →
   :approved → :ordered → :received (plus :rejected and :cancelled
   escapes)."
  (:require [datahike.api :as d]
            [kontor.procurement.receipt :as receipt]
            [kontor.status-machine :as sm]
            [kontor.validation :as validation]))

;; ============================================================================
;; Resolution
;; ============================================================================

(defn by-external-id
  [db external-id]
  (d/q '[:find ?e .
         :in $ ?xid
         :where [?e :kontor.requirement/external-id ?xid]]
       db external-id))

(defn resolve-requirement
  [db spec]
  (cond
    (nil? spec)    nil
    (string? spec) (by-external-id db spec)
    :else          spec))

(defn pull-requirement
  "Pull a requirement with commitments + linked POs."
  [db spec]
  (when-let [eid (resolve-requirement db spec)]
    (d/pull db
            '[* {:kontor.requirement/entity [:kontor.entity/code :kontor.entity/name]
                 :kontor.requirement/budget-commodity [:kontor.commodity/symbol]
                 :kontor.requirement/cost-center [:kontor.analytic-account/code :kontor.analytic-account/name]}]
            eid)))

(defn commitments-of
  "Return :requirement-commitment rows for a requirement, pulled
   with the linked :order-item."
  [db spec]
  (when-let [eid (resolve-requirement db spec)]
    (->> (d/q '[:find [?c ...]
                :in $ ?req
                :where [?c :kontor.requirement-commitment/requirement ?req]]
              db eid)
         (map #(d/pull db '[* {:kontor.requirement-commitment/order-item
                                [:kontor.sales.order-item/seq-id :kontor.sales.order-item/product-id
                                 :kontor.sales.order-item/quantity
                                 {:kontor.sales.order-item/order [:kontor.order/external-id]}]}] %))
         vec)))

;; ============================================================================
;; Transactors
;; ============================================================================

(declare make-requirement-tx-data commit-to-po-tx-data)

(defn make-requirement-tx-data
  "Pure tx-data builder for `make-requirement!` (ADR-068)."
  [_db {:keys [external-id type product-id quantity uom facility-id
               facility-to-id required-by-date start-date
               estimated-budget budget-commodity entity cost-center
               justification description created-by-uid created-at]
        :or {type :product}}]
  (when-not external-id  (throw (ex-info ":external-id required" {})))
  (when-not product-id   (throw (ex-info ":product-id required" {})))
  (when-not quantity     (throw (ex-info ":quantity required" {})))
  (when-not facility-id  (throw (ex-info ":facility-id required" {})))
  (let [row (cond-> {:kontor.requirement/external-id external-id
                     :kontor.requirement/type type
                     :kontor.requirement/status :proposed
                     :kontor.requirement/product-id product-id
                     :kontor.requirement/quantity quantity
                     :kontor.requirement/facility-id facility-id
                     :kontor.requirement/created-at (or created-at (java.util.Date.))}
              uom              (assoc :kontor.requirement/uom uom)
              facility-to-id   (assoc :kontor.requirement/facility-to-id facility-to-id)
              required-by-date (assoc :kontor.requirement/required-by-date required-by-date)
              start-date       (assoc :kontor.requirement/start-date start-date)
              estimated-budget (assoc :kontor.requirement/estimated-budget estimated-budget)
              budget-commodity (assoc :kontor.requirement/budget-commodity budget-commodity)
              entity           (assoc :kontor.requirement/entity entity)
              cost-center      (assoc :kontor.requirement/cost-center cost-center)
              justification    (assoc :kontor.requirement/justification justification)
              description      (assoc :kontor.requirement/description description)
              created-by-uid   (assoc :kontor.requirement/created-by-uid created-by-uid))]
    [row]))

(defn make-requirement!
  "Create a `:requirement` entity in `:proposed` state. Routes through
   the gate (ADR-068).

   Required keys in opts:
     :external-id, :product-id, :quantity, :facility-id.

   Optional:
     :type (default :product), :uom, :facility-to-id, :required-by-date,
     :start-date, :estimated-budget, :budget-commodity, :entity,
     :cost-center, :justification, :description, :created-by-uid.

   Returns the tx-report.

   The pure tx-data builder is `make-requirement-tx-data`."
  [conn opts]
  (validation/transact-with-validation
   conn (make-requirement-tx-data (d/db conn)
                                  (assoc opts :created-at (java.util.Date.)))))

(defn approve-requirement!
  "Transition :proposed → :approved. Runs ADR-038 :approval-policy
   checks (e.g., :no-self-approval, :requires-supporting-doc) via
   record-status-change!."
  ([conn requirement] (approve-requirement! conn requirement nil))
  ([conn requirement opts]
   (let [eid (resolve-requirement (d/db conn) requirement)]
     (sm/record-status-change! conn
                               (merge {:entity eid
                                       :entity-type :requirement
                                       :facet :kontor.requirement/status
                                       :to :approved}
                                      opts)))))

(defn reject-requirement!
  ([conn requirement] (reject-requirement! conn requirement nil))
  ([conn requirement opts]
   (let [eid (resolve-requirement (d/db conn) requirement)]
     (sm/record-status-change! conn
                               (merge {:entity eid
                                       :entity-type :requirement
                                       :facet :kontor.requirement/status
                                       :to :rejected}
                                      opts)))))

(defn cancel-requirement!
  ([conn requirement] (cancel-requirement! conn requirement nil))
  ([conn requirement opts]
   (let [eid (resolve-requirement (d/db conn) requirement)]
     (sm/record-status-change! conn
                               (merge {:entity eid
                                       :entity-type :requirement
                                       :facet :kontor.requirement/status
                                       :to :cancelled}
                                      opts)))))

(defn commit-to-po-tx-data
  "Pure tx-data builder for `commit-to-po!` (ADR-068)."
  [db {:keys [requirement order-item quantity skip-status-advance?
              committed-at]}]
  (let [req-eid (resolve-requirement db requirement)
        commitment {:kontor.requirement-commitment/requirement req-eid
                    :kontor.requirement-commitment/order-item order-item
                    :kontor.requirement-commitment/quantity quantity
                    :kontor.requirement-commitment/committed-at
                    (or committed-at (java.util.Date.))}]
    (if (and (not skip-status-advance?)
             (sm/legal-transition? db :requirement
                                   :kontor.requirement/status
                                   :approved :ordered))
      (vec (concat [commitment]
                   (sm/record-status-change-tx-data
                    db
                    {:entity req-eid
                     :entity-type :requirement
                     :facet :kontor.requirement/status
                     :to :ordered
                     :reason :auto-promoted})))
      [commitment])))

(defn commit-to-po!
  "Link a requirement to a PO line via :requirement-commitment, in
   the same tx advance :kontor.requirement/status :approved → :ordered.
   The composite identity tuple makes the junction idempotent.
   Routes through the gate (ADR-068).

   Required: :requirement, :order-item, :quantity.

   The status transition only fires if it would be legal; for partial
   commitment (qty < requirement-quantity), caller can pass
   :skip-status-advance? true to defer the :approved → :ordered
   promotion until full commitment via auto-promote-to-ordered!.

   The pure tx-data builder is `commit-to-po-tx-data`."
  [conn opts]
  (validation/transact-with-validation
   conn (commit-to-po-tx-data (d/db conn)
                              (assoc opts :committed-at (java.util.Date.)))))

(defn auto-promote-to-received!
  "When all linked POs for a requirement are fully received,
   advance :kontor.requirement/status :ordered → :received.

   No-op for requirements not yet :ordered or already :received."
  [conn requirement]
  (let [db (d/db conn)
        req-eid (resolve-requirement db requirement)
        current (sm/current-status db req-eid :kontor.requirement/status)]
    (when (= :ordered current)
      ;; For v1: simple check — all commitments have receipts with
      ;; matching qty. Detailed math (qty-accepted vs committed qty)
      ;; lives in kontor.procurement.match; here we just check
      ;; existence.
      (let [commitments (commitments-of db req-eid)
            fully-received? (every?
                             (fn [{:kontor.requirement-commitment/keys [order-item quantity]}]
                               (let [oi-eid (:db/id order-item)
                                     received (receipt/quantity-received-of-order-item
                                               db oi-eid)]
                                 (>= (.compareTo ^java.math.BigDecimal received
                                                 ^java.math.BigDecimal quantity)
                                     0)))
                             commitments)]
        (when fully-received?
          (sm/record-status-change! conn
                                    {:entity req-eid
                                     :entity-type :requirement
                                     :facet :kontor.requirement/status
                                     :to :received
                                     :reason :auto-promoted}))))))

;; ============================================================================
;; Queries
;; ============================================================================

(defn requirements-by-status
  "All requirements currently at `status`."
  [db status]
  (->> (d/q '[:find [?r ...]
              :in $ ?st
              :where [?r :kontor.requirement/status ?st]]
            db status)
       (map #(d/pull db '[*] %))
       vec))

(defn pending-of-supplier
  "Approved requirements that haven't been fully committed to POs of
   a given supplier. Lookup by :kontor.order-role/role-type :supplier =
   the supplier on the committed PO. Returns requirements eligible
   for inclusion in a new PO to this supplier."
  [db supplier-partner-eid]
  (->> (d/q '[:find [?req ...]
              :in $ ?sup
              :where
              [?req :kontor.requirement/status :approved]
              ;; Has zero or partial commitments
              (not-join [?req ?sup]
                        ;; Anti-pattern: skip if already fully committed
                        ;; to this supplier
                        [?c :kontor.requirement-commitment/requirement ?req]
                        [?c :kontor.requirement-commitment/order-item ?oi]
                        [?oi :kontor.sales.order-item/order ?o]
                        [?r :kontor.order-role/order ?o]
                        [?r :kontor.order-role/role-type :supplier]
                        [?r :kontor.order-role/partner ?sup])]
            db supplier-partner-eid)
       (map #(d/pull db '[*] %))
       vec))

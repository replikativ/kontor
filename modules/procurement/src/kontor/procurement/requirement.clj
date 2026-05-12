(ns kontor.procurement.requirement
  "Requirement (requisition) helpers — ADR-042.

   A `:requirement` is a demand signal independent of any order. It
   may roll into one or more POs via the `:requirement-commitment`
   junction (many-to-many). The state machine: nil → :proposed →
   :approved → :ordered → :received (plus :rejected and :cancelled
   escapes)."
  (:require [datahike.api :as d]
            [kontor.procurement.receipt :as receipt]
            [kontor.status-machine :as sm]))

;; ============================================================================
;; Resolution
;; ============================================================================

(defn by-external-id
  [db external-id]
  (d/q '[:find ?e .
         :in $ ?xid
         :where [?e :requirement/external-id ?xid]]
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
            '[* {:requirement/entity [:entity/code :entity/name]
                 :requirement/budget-commodity [:commodity/symbol]
                 :requirement/cost-center [:analytic-account/code :analytic-account/name]}]
            eid)))

(defn commitments-of
  "Return :requirement-commitment rows for a requirement, pulled
   with the linked :order-item."
  [db spec]
  (when-let [eid (resolve-requirement db spec)]
    (->> (d/q '[:find [?c ...]
                :in $ ?req
                :where [?c :requirement-commitment/requirement ?req]]
              db eid)
         (map #(d/pull db '[* {:requirement-commitment/order-item
                                [:order-item/seq-id :order-item/product-id
                                 :order-item/quantity
                                 {:order-item/order [:order/external-id]}]}] %))
         vec)))

;; ============================================================================
;; Transactors
;; ============================================================================

(defn make-requirement!
  "Create a `:requirement` entity in `:proposed` state.

   Required keys in opts:
     :external-id, :product-id, :quantity, :facility-id.

   Optional:
     :type (default :product), :uom, :facility-to-id, :required-by-date,
     :start-date, :estimated-budget, :budget-commodity, :entity,
     :cost-center, :justification, :description, :created-by-uid.

   Returns the tx-report."
  [conn {:keys [external-id type product-id quantity uom facility-id
                facility-to-id required-by-date start-date
                estimated-budget budget-commodity entity cost-center
                justification description created-by-uid]
         :or {type :product}}]
  (when-not external-id  (throw (ex-info ":external-id required" {})))
  (when-not product-id   (throw (ex-info ":product-id required" {})))
  (when-not quantity     (throw (ex-info ":quantity required" {})))
  (when-not facility-id  (throw (ex-info ":facility-id required" {})))
  (let [row (cond-> {:requirement/external-id external-id
                     :requirement/type type
                     :requirement/status :proposed
                     :requirement/product-id product-id
                     :requirement/quantity quantity
                     :requirement/facility-id facility-id
                     :requirement/created-at (java.util.Date.)}
              uom              (assoc :requirement/uom uom)
              facility-to-id   (assoc :requirement/facility-to-id facility-to-id)
              required-by-date (assoc :requirement/required-by-date required-by-date)
              start-date       (assoc :requirement/start-date start-date)
              estimated-budget (assoc :requirement/estimated-budget estimated-budget)
              budget-commodity (assoc :requirement/budget-commodity budget-commodity)
              entity           (assoc :requirement/entity entity)
              cost-center      (assoc :requirement/cost-center cost-center)
              justification    (assoc :requirement/justification justification)
              description      (assoc :requirement/description description)
              created-by-uid   (assoc :requirement/created-by-uid created-by-uid))]
    (d/transact conn [row])))

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
                                       :facet :requirement/status
                                       :to :approved}
                                      opts)))))

(defn reject-requirement!
  ([conn requirement] (reject-requirement! conn requirement nil))
  ([conn requirement opts]
   (let [eid (resolve-requirement (d/db conn) requirement)]
     (sm/record-status-change! conn
                               (merge {:entity eid
                                       :entity-type :requirement
                                       :facet :requirement/status
                                       :to :rejected}
                                      opts)))))

(defn cancel-requirement!
  ([conn requirement] (cancel-requirement! conn requirement nil))
  ([conn requirement opts]
   (let [eid (resolve-requirement (d/db conn) requirement)]
     (sm/record-status-change! conn
                               (merge {:entity eid
                                       :entity-type :requirement
                                       :facet :requirement/status
                                       :to :cancelled}
                                      opts)))))

(defn commit-to-po!
  "Link a requirement to a PO line via :requirement-commitment, in
   the same tx advance :requirement/status :approved → :ordered.
   The composite identity tuple makes the junction idempotent.

   Required: :requirement, :order-item, :quantity.

   The status transition only fires if it would be legal; for partial
   commitment (qty < requirement-quantity), caller can pass
   :skip-status-advance? true to defer the :approved → :ordered
   promotion until full commitment via auto-promote-to-ordered!."
  [conn {:keys [requirement order-item quantity skip-status-advance?]}]
  (let [db (d/db conn)
        req-eid (resolve-requirement db requirement)
        commitment {:requirement-commitment/requirement req-eid
                    :requirement-commitment/order-item order-item
                    :requirement-commitment/quantity quantity
                    :requirement-commitment/committed-at (java.util.Date.)}
        all-tx (if (and (not skip-status-advance?)
                        (sm/legal-transition? db :requirement
                                              :requirement/status
                                              :approved :ordered))
                 (vec (concat [commitment]
                              (sm/record-status-change-tx-data
                               db
                               {:entity req-eid
                                :entity-type :requirement
                                :facet :requirement/status
                                :to :ordered
                                :reason :auto-promoted})))
                 [commitment])]
    (d/transact conn all-tx)))

(defn auto-promote-to-received!
  "When all linked POs for a requirement are fully received,
   advance :requirement/status :ordered → :received.

   No-op for requirements not yet :ordered or already :received."
  [conn requirement]
  (let [db (d/db conn)
        req-eid (resolve-requirement db requirement)
        current (sm/current-status db req-eid :requirement/status)]
    (when (= :ordered current)
      ;; For v1: simple check — all commitments have receipts with
      ;; matching qty. Detailed math (qty-accepted vs committed qty)
      ;; lives in kontor.procurement.match; here we just check
      ;; existence.
      (let [commitments (commitments-of db req-eid)
            fully-received? (every?
                             (fn [{:requirement-commitment/keys [order-item quantity]}]
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
                                     :facet :requirement/status
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
              :where [?r :requirement/status ?st]]
            db status)
       (map #(d/pull db '[*] %))
       vec))

(defn pending-of-supplier
  "Approved requirements that haven't been fully committed to POs of
   a given supplier. Lookup by :order-role/role-type :supplier =
   the supplier on the committed PO. Returns requirements eligible
   for inclusion in a new PO to this supplier."
  [db supplier-partner-eid]
  (->> (d/q '[:find [?req ...]
              :in $ ?sup
              :where
              [?req :requirement/status :approved]
              ;; Has zero or partial commitments
              (not-join [?req ?sup]
                        ;; Anti-pattern: skip if already fully committed
                        ;; to this supplier
                        [?c :requirement-commitment/requirement ?req]
                        [?c :requirement-commitment/order-item ?oi]
                        [?oi :order-item/order ?o]
                        [?r :order-role/order ?o]
                        [?r :order-role/role-type :supplier]
                        [?r :order-role/partner ?sup])]
            db supplier-partner-eid)
       (map #(d/pull db '[*] %))
       vec))

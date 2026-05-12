(ns kontor.procurement.match
  "3-way match — ADR-042.

   3-way match is a referential invariant: for each PO line,
   `(received-qty - returned-qty) ≈ (invoiced-qty - credited-qty)`
   within tolerance bands. The match-status state machine on
   `:invoice/match-status` (ADR-034 facet) captures the operational
   view; the FKs (`:order-item-billing`, `:receipt-invoice-billing`,
   `:return-item-billing`) ARE the source of truth.

   This namespace ships the tolerance-policy lookup + match-report
   query + state-machine driver. The actual posting-time enforcement
   happens via ADR-038's `:approval-policy/rule :requires-three-way-
   match-pass` consulted by kontor.invoice.posting/post-to-ledger!."
  (:require [datahike.api :as d]
            [kontor.status-machine :as sm]
            [kontor.procurement.receipt :as receipt]))

;; ============================================================================
;; Tolerance policy lookup
;; ============================================================================

(defn applicable-tolerance
  "Three-tier priority lookup for `:match-tolerance`:
     1. (entity, supplier, product) — most specific.
     2. (entity, supplier, nil) — supplier-wide default.
     3. (entity, nil, nil) — entity-wide default.
     4. nil — strict match (0% over, 0 absolute).

   Returns the pulled `:match-tolerance` map or nil."
  [db {:keys [entity supplier product-id]}]
  (let [pull-tolerance (fn [tol-eid]
                         (when tol-eid (d/pull db '[*] tol-eid)))
        ;; Tier 1: most specific
        t1 (when (and entity supplier product-id)
             (d/q '[:find ?t .
                    :in $ ?e ?s ?p
                    :where
                    [?t :match-tolerance/entity ?e]
                    [?t :match-tolerance/supplier ?s]
                    [?t :match-tolerance/product-id ?p]
                    [?t :match-tolerance/active true]]
                  db entity supplier product-id))
        ;; Tier 2: entity + supplier, no product
        t2 (when (and entity supplier)
             (d/q '[:find ?t .
                    :in $ ?e ?s
                    :where
                    [?t :match-tolerance/entity ?e]
                    [?t :match-tolerance/supplier ?s]
                    [(missing? $ ?t :match-tolerance/product-id)]
                    [?t :match-tolerance/active true]]
                  db entity supplier))
        ;; Tier 3: entity only
        t3 (when entity
             (d/q '[:find ?t .
                    :in $ ?e
                    :where
                    [?t :match-tolerance/entity ?e]
                    [(missing? $ ?t :match-tolerance/supplier)]
                    [(missing? $ ?t :match-tolerance/product-id)]
                    [?t :match-tolerance/active true]]
                  db entity))]
    (or (pull-tolerance t1)
        (pull-tolerance t2)
        (pull-tolerance t3))))

(defn- big ^java.math.BigDecimal [x] (or x 0M))

(defn- within-tolerance?
  "Check whether `(invoiced − allowable-by-PO)` is within tolerance.
   delta is the deviation; tol is the tolerance map (or nil for
   strict)."
  [^java.math.BigDecimal pct-bound
   ^java.math.BigDecimal abs-bound
   ^java.math.BigDecimal base
   ^java.math.BigDecimal delta]
  (let [pct-allowance (if (and pct-bound (pos? (.signum pct-bound)))
                        (.multiply base pct-bound)
                        0M)
        abs-allowance (or abs-bound 0M)
        allowance (.max pct-allowance abs-allowance)]
    (<= (.compareTo (.abs delta) allowance) 0)))

;; ============================================================================
;; Match report
;; ============================================================================

(defn three-way-report
  "For each line on an invoice, compute the 3-way match report:
     {:order-item ... :ordered-qty :received-qty :invoiced-qty
      :ordered-unit-price :invoiced-unit-price
      :qty-delta :price-delta
      :verdict :match | :within-tolerance | :exception-qty |
                :exception-price | :exception-missing-receipt}.

   Tolerance applied per-(entity, supplier, product-id) lookup."
  [db invoice-eid]
  (let [invoice (d/pull db
                        '[* {:invoice/order [:db/id]
                             :invoice/entity [:db/id]
                             :invoice/seller [:db/id]}]
                        invoice-eid)
        entity-eid (get-in invoice [:invoice/entity :db/id])
        supplier-eid (get-in invoice [:invoice/seller :db/id])
        lines (->> (d/q '[:find [?l ...]
                          :in $ ?inv
                          :where [?l :invoice-line/invoice ?inv]]
                        db invoice-eid)
                   (map #(d/pull db '[* {:invoice-line/order-item [:db/id
                                                                    :order-item/product-id
                                                                    :order-item/quantity
                                                                    :order-item/unit-price
                                                                    :order-item/requires-receipt?]}] %)))]
    (mapv (fn [line]
            (let [oi (:invoice-line/order-item line)
                  oi-eid (:db/id oi)
                  ordered-qty (or (:order-item/quantity oi) 0M)
                  ordered-price (or (:order-item/unit-price oi) 0M)
                  invoiced-qty (or (:invoice-line/quantity line) 0M)
                  invoiced-price (or (:invoice-line/unit-price line) 0M)
                  received-qty (if (false? (:order-item/requires-receipt? oi))
                                 ;; service line — use service-acceptance
                                 (or (d/q '[:find (sum ?q) .
                                            :with ?sa
                                            :in $ ?oi
                                            :where
                                            [?sa :service-acceptance/order-item ?oi]
                                            [?sa :service-acceptance/quantity-accepted ?q]]
                                          db oi-eid) 0M)
                                 (receipt/quantity-received-of-order-item db oi-eid))
                  qty-delta (.subtract ^java.math.BigDecimal (big invoiced-qty)
                                       ^java.math.BigDecimal (big received-qty))
                  price-delta (.subtract ^java.math.BigDecimal (big invoiced-price)
                                         ^java.math.BigDecimal (big ordered-price))
                  tol (applicable-tolerance db {:entity entity-eid
                                                :supplier supplier-eid
                                                :product-id (:order-item/product-id oi)})
                  qty-ok? (within-tolerance? (:match-tolerance/qty-pct-over tol)
                                             (:match-tolerance/qty-abs-over tol)
                                             (big received-qty) qty-delta)
                  price-ok? (within-tolerance? (:match-tolerance/price-pct-over tol)
                                               (:match-tolerance/price-abs-over tol)
                                               (big ordered-price) price-delta)
                  missing-receipt? (and (zero? (.signum ^java.math.BigDecimal (big received-qty)))
                                        (not (false? (:order-item/requires-receipt? oi))))
                  verdict (cond
                            missing-receipt? :exception-missing-receipt
                            (and qty-ok? price-ok?
                                 (zero? (.signum ^java.math.BigDecimal qty-delta))
                                 (zero? (.signum ^java.math.BigDecimal price-delta)))
                            :match
                            (and qty-ok? price-ok?) :within-tolerance
                            (not qty-ok?) :exception-qty
                            :else :exception-price)]
              {:invoice-line (:db/id line)
               :order-item oi-eid
               :product-id (:order-item/product-id oi)
               :ordered-qty ordered-qty
               :received-qty received-qty
               :invoiced-qty invoiced-qty
               :ordered-unit-price ordered-price
               :invoiced-unit-price invoiced-price
               :qty-delta qty-delta
               :price-delta price-delta
               :tolerance tol
               :verdict verdict}))
          lines)))

(defn invoice-verdict
  "Roll up per-line verdicts to a single invoice-level :match-status.
     - all :match / :within-tolerance → :auto-matched
     - any :exception-missing-receipt → :exception-missing-receipt
     - any :exception-qty → :exception-qty
     - any :exception-price → :exception-price"
  [report]
  (cond
    (empty? report) :nil
    (some #(= :exception-missing-receipt (:verdict %)) report) :exception-missing-receipt
    (some #(= :exception-qty (:verdict %)) report) :exception-qty
    (some #(= :exception-price (:verdict %)) report) :exception-price
    :else :auto-matched))

;; ============================================================================
;; State-machine driver
;; ============================================================================

(defn recompute-match-status!
  "Compute the 3-way report for an invoice and write the rolled-up
   verdict via record-status-change!. Returns the verdict."
  ([conn invoice-eid] (recompute-match-status! conn invoice-eid nil))
  ([conn invoice-eid opts]
   (let [db (d/db conn)
         report (three-way-report db invoice-eid)
         verdict (invoice-verdict report)
         current (sm/current-status db invoice-eid :invoice/match-status)]
     (when (and (not= :nil verdict)
                (sm/legal-transition? db :invoice :invoice/match-status
                                      current verdict))
       (sm/record-status-change! conn
                                 (merge {:entity invoice-eid
                                         :entity-type :invoice
                                         :facet :invoice/match-status
                                         :to verdict}
                                        opts)))
     verdict)))

(defn match-status-of-invoice
  "Read the current :invoice/match-status (denormalized)."
  [db invoice-eid]
  (sm/current-status db invoice-eid :invoice/match-status))

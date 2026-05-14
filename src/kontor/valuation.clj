(ns kontor.valuation
  "Valuation books + layers + consumption + adjustments — ADR-027/028.

   This namespace provides:

   - Bootstrap of the primary valuation book (sibling of
     `kontor.ledger/install-defaults!` from ADR-021).
   - Entity helpers: resolve books by code; resolve layers by their
     (book, item, lot) coordinates.
   - **View computations** that derive remaining quantity, current
     unit cost, on-hand quantity, and on-hand value from the
     append-only fact log. None of these views are materialized;
     all are computed from datalog on demand.

   These helpers are used by `kontor.costing-provider` (which plans
   which layers to consume) and by `kontor.posting/plan-stock-move`
   (which assembles the kernel transaction)."
  (:require [datahike.api :as d]))

;; ============================================================================
;; Valuation book — bootstrap + resolution
;; ============================================================================

(def primary-code
  "The bootstrap valuation book's stable identifier."
  "primary")

(def primary-seed
  "Seed data for the primary valuation book. Idempotent via
   `:db.unique/identity` on `:valuation-book/code`."
  {:valuation-book/code        primary-code
   :valuation-book/name        "Primary valuation book"
   :valuation-book/framework   :legal
   :valuation-book/cost-method :fifo
   :valuation-book/active      true})

(defn install-defaults!
  "Idempotently transact the primary valuation book."
  [conn]
  (d/transact conn [primary-seed]))

(defn primary
  "Resolve the primary valuation book entity-id, or nil if not
   installed."
  [db]
  (d/q '[:find ?e .
         :in $ ?code
         :where [?e :valuation-book/code ?code]]
       db primary-code))

(defn by-code
  "Resolve a valuation book entity-id by its `:valuation-book/code`."
  [db code]
  (d/q '[:find ?e .
         :in $ ?code
         :where [?e :valuation-book/code ?code]]
       db code))

(defn resolve-book
  "Coerce `book-spec` to an entity-id. Accepts:
     - nil       → primary book (or nil if not installed)
     - a string  → looked up by `:valuation-book/code`
     - a long    → returned as-is (assumed eid)
     - a map     → assumed lookup ref or pulled entity"
  [db book-spec]
  (cond
    (nil? book-spec)    (primary db)
    (string? book-spec) (by-code db book-spec)
    :else               book-spec))

;; ============================================================================
;; Views — derived from the append-only fact log
;;
;; Every public view takes an optional opts map:
;;
;;   :as-of-valid     instant. Filters events whose valid time is
;;                    strictly AFTER this instant. nil (default) =
;;                    no upper bound. Matches the ADR-008 bitemporal
;;                    contract that `kontor.balance` already follows.
;;   :include-states  set of `:transaction/state` keywords whose
;;                    events are included. Layers / consumption /
;;                    adjustment events whose origin transaction is
;;                    NOT in this set are filtered out. Default:
;;                    `#{:posted :draft :pending-attestation}`
;;                    (everything except :cancelled).
;;
;; Tx-time filtering happens before this layer — pass `(d/as-of db
;; instant)` as the db value to scope to a historical tx snapshot.
;; ============================================================================

(def default-include-states
  "States that count as 'real' for inventory-view purposes. Cancelled
   transactions are excluded by default; consumers that want to see
   them pass an explicit `:include-states` set."
  #{:posted :draft :pending-attestation})

(defn- before-or-eq? [^java.util.Date a ^java.util.Date b]
  (<= (.compareTo a b) 0))

(defn- event-included?
  "Filter helper: an event with origin-transaction `tx-eid` is
   included iff its origin tx's state is in `include-states` and its
   valid-time `event-instant` is on-or-before `as-of-valid`. Either
   bound may be nil (no filter)."
  [db tx-eid include-states event-instant as-of-valid]
  (and (or (nil? as-of-valid)
           (and (some? event-instant)
                (before-or-eq? event-instant as-of-valid)))
       (let [state (d/q '[:find ?s . :in $ ?t :where
                          [?t :transaction/state ?s]]
                        db tx-eid)]
         (or (nil? state)
             (contains? include-states state)))))

(defn qty-consumed
  "Total quantity drawn from `layer-eid` across all consumption events
   that are included under the opts filter."
  (^java.math.BigDecimal [db layer-eid] (qty-consumed db layer-eid {}))
  (^java.math.BigDecimal [db layer-eid {:keys [as-of-valid include-states]
                                        :or {include-states default-include-states}}]
   (let [rows (d/q '[:find ?q ?tx ?issued
                     :in $ ?layer
                     :where
                     [?c :layer-consumption/layer ?layer]
                     [?c :layer-consumption/qty ?q]
                     [?c :layer-consumption/issue-transaction ?tx]
                     [?c :layer-consumption/issued-at ?issued]]
                   db layer-eid)]
     (reduce (fn [^java.math.BigDecimal acc [^java.math.BigDecimal q tx issued]]
               (if (event-included? db tx include-states issued as-of-valid)
                 (.add acc q)
                 acc))
             0M
             rows))))

(defn qty-remaining
  "Remaining quantity on a layer at the given bitemporal cursor.
     qty-remaining = qty-original − Σ qty-consumed (filtered)"
  (^java.math.BigDecimal [db layer-eid] (qty-remaining db layer-eid {}))
  (^java.math.BigDecimal [db layer-eid opts]
   (let [orig (d/q '[:find ?q .
                     :in $ ?l
                     :where [?l :valuation-layer/qty-original ?q]]
                   db layer-eid)]
     (if orig
       (.subtract ^java.math.BigDecimal orig
                  ^java.math.BigDecimal (qty-consumed db layer-eid opts))
       0M))))

(defn adjustment-total
  "Sum of `:layer-adjustment/amount` for the given layer, scoped by opts."
  (^java.math.BigDecimal [db layer-eid] (adjustment-total db layer-eid {}))
  (^java.math.BigDecimal [db layer-eid {:keys [as-of-valid include-states]
                                        :or {include-states default-include-states}}]
   (let [rows (d/q '[:find ?a ?tx ?applied
                     :in $ ?l
                     :where
                     [?adj :layer-adjustment/layer ?l]
                     [?adj :layer-adjustment/amount ?a]
                     [?adj :layer-adjustment/origin-transaction ?tx]
                     [?adj :layer-adjustment/applied-at ?applied]]
                   db layer-eid)]
     (reduce (fn [^java.math.BigDecimal acc [^java.math.BigDecimal a tx applied]]
               (if (event-included? db tx include-states applied as-of-valid)
                 (.add acc a)
                 acc))
             0M
             rows))))

(defn current-unit-cost
  "Effective unit cost on a layer after all adjustments visible at
   the bitemporal cursor:
     (qty-original × unit-cost-original + Σ adjustments) / qty-original

   Rounding: HALF_EVEN at 4 decimal places."
  (^java.math.BigDecimal [db layer-eid] (current-unit-cost db layer-eid {}))
  (^java.math.BigDecimal [db layer-eid opts]
   (let [pulled (d/pull db
                        [:valuation-layer/qty-original
                         :valuation-layer/unit-cost-original]
                        layer-eid)
         qty-original  ^java.math.BigDecimal (:valuation-layer/qty-original pulled)
         unit-original ^java.math.BigDecimal (:valuation-layer/unit-cost-original pulled)
         adj-total     ^java.math.BigDecimal (adjustment-total db layer-eid opts)]
     (if (or (nil? qty-original) (zero? (.signum qty-original)))
       0M
       (let [total-cost (.add (.multiply qty-original unit-original) adj-total)]
         (.divide total-cost qty-original
                  4 java.math.RoundingMode/HALF_EVEN))))))

(defn- layer-expires-at
  "The `:lot/expires-at` of a layer's lot, or nil. Used by the
   `:order-by :expires-at` (FEFO) layer ordering."
  ^java.util.Date [db layer-eid]
  (:lot/expires-at
   (:valuation-layer/lot
    (d/pull db [{:valuation-layer/lot [:lot/expires-at]}] layer-eid))))

(defn available-layers
  "All layers with positive remaining quantity for the given
   (book, item) pair, scoped by opts. Returns entity-ids ordered by
   `:valuation-layer/received-at` ascending, with layer eid as the
   deterministic tie-breaker (FIFO order).

   Lot-aware: passing a non-nil `lot` restricts to layers in that lot.

   Bitemporal: `:as-of-valid` excludes layers received after the
   cursor AND adjusts consumption sums to only count consumptions
   issued at-or-before the cursor.

   Cancelled-transaction filter: layers whose origin transaction's
   `:transaction/state` is NOT in `:include-states` are excluded.
   Default include-states excludes `:cancelled`.

   Ordering: `:order-by` is `:received-at` (default — FIFO) or
   `:expires-at` (FEFO — nearest lot expiry first, `:received-at`
   then eid as the tie-break; layers with no lot expiry sort last).
   The FEFO ordering is what a `FefoCostingProvider` and the
   `:fifo-exp` reservation walk consume.

   Implementation: two datalog queries (candidate layers + consumed
   sums per layer), then a Clojure pass that applies the bitemporal
   + state filters. O(2) DB queries regardless of layer count."
  ([db book item] (available-layers db book item nil {}))
  ([db book item lot] (available-layers db book item lot {}))
  ([db book item lot {:keys [as-of-valid include-states order-by]
                      :or {include-states default-include-states
                           order-by :received-at}}]
   (let [candidate-rows
         (if (some? lot)
           (d/q '[:find ?l ?orig ?received ?tx
                  :in $ ?book ?item ?lot
                  :where
                  [?l :valuation-layer/book ?book]
                  [?l :valuation-layer/item ?item]
                  [?l :valuation-layer/lot ?lot]
                  [?l :valuation-layer/qty-original ?orig]
                  [?l :valuation-layer/received-at ?received]
                  [?l :valuation-layer/origin-transaction ?tx]]
                db book item lot)
           (d/q '[:find ?l ?orig ?received ?tx
                  :in $ ?book ?item
                  :where
                  [?l :valuation-layer/book ?book]
                  [?l :valuation-layer/item ?item]
                  [?l :valuation-layer/qty-original ?orig]
                  [?l :valuation-layer/received-at ?received]
                  [?l :valuation-layer/origin-transaction ?tx]]
                db book item))]
     (->> candidate-rows
          (keep (fn [[layer ^java.math.BigDecimal orig received tx]]
                  (when (event-included? db tx include-states received as-of-valid)
                    (let [consumed (qty-consumed db layer {:as-of-valid as-of-valid
                                                           :include-states include-states})
                          remaining (.subtract orig ^java.math.BigDecimal consumed)]
                      (when (pos? (.signum remaining))
                        [layer received])))))
          ;; Default: received-at ascending, eid tie-break (FIFO).
          ;; :expires-at: nearest lot expiry first (FEFO), received-at
          ;; then eid tie-break; no-expiry layers sort last.
          (sort-by (case order-by
                     :expires-at
                     (fn [[layer received]]
                       (let [exp (layer-expires-at db layer)]
                         [(if exp (.getTime ^java.util.Date exp) Long/MAX_VALUE)
                          received layer]))
                     ;; :received-at (default)
                     (juxt second first)))
          (mapv first)))))

(defn on-hand-qty
  "Total quantity on hand for a (book, item) pair at the bitemporal
   cursor in opts."
  (^java.math.BigDecimal [db book item] (on-hand-qty db book item {}))
  (^java.math.BigDecimal [db book item opts]
   (reduce (fn [^java.math.BigDecimal acc layer]
             (.add acc (qty-remaining db layer opts)))
           0M
           (available-layers db book item nil opts))))

(defn on-hand-value
  "Total accounting value on hand: Σ (qty-remaining × current-unit-cost)
   across all in-scope layers."
  (^java.math.BigDecimal [db book item] (on-hand-value db book item {}))
  (^java.math.BigDecimal [db book item opts]
   (reduce (fn [^java.math.BigDecimal acc layer]
             (let [q ^java.math.BigDecimal (qty-remaining db layer opts)
                   c ^java.math.BigDecimal (current-unit-cost db layer opts)]
               (.add acc (.multiply q c))))
           0M
           (available-layers db book item nil opts))))

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
;; ============================================================================

(defn qty-consumed
  "Total quantity drawn from `layer-eid` across all consumption events.

   Pure datalog aggregate. Returns 0M when no consumption exists."
  ^java.math.BigDecimal [db layer-eid]
  (or (d/q '[:find (sum ?q) .
             :in $ ?layer
             :where
             [?c :layer-consumption/layer ?layer]
             [?c :layer-consumption/qty ?q]]
           db layer-eid)
      0M))

(defn qty-remaining
  "Remaining quantity on a layer.
     qty-remaining = qty-original − Σ consumption.qty"
  ^java.math.BigDecimal [db layer-eid]
  (let [orig (d/q '[:find ?q .
                    :in $ ?l
                    :where [?l :valuation-layer/qty-original ?q]]
                  db layer-eid)]
    (if orig
      (.subtract ^java.math.BigDecimal orig
                 ^java.math.BigDecimal (qty-consumed db layer-eid))
      0M)))

(defn adjustment-total
  "Sum of `:layer-adjustment/amount` for the given layer."
  ^java.math.BigDecimal [db layer-eid]
  (or (d/q '[:find (sum ?a) .
             :in $ ?l
             :where
             [?adj :layer-adjustment/layer ?l]
             [?adj :layer-adjustment/amount ?a]]
           db layer-eid)
      0M))

(defn current-unit-cost
  "Effective unit cost on a layer after all adjustments:
     (qty-original × unit-cost-original + Σ adjustments) / qty-original

   Rounding: HALF_EVEN at 4 decimal places by default (sufficient
   for cost-basis arithmetic; downstream Money rounding happens at
   the posting layer)."
  ^java.math.BigDecimal [db layer-eid]
  (let [pulled (d/pull db
                       [:valuation-layer/qty-original
                        :valuation-layer/unit-cost-original]
                       layer-eid)
        qty-original  ^java.math.BigDecimal (:valuation-layer/qty-original pulled)
        unit-original ^java.math.BigDecimal (:valuation-layer/unit-cost-original pulled)
        adj-total     ^java.math.BigDecimal (adjustment-total db layer-eid)]
    (if (or (nil? qty-original) (zero? (.signum qty-original)))
      0M
      (let [total-cost (.add (.multiply qty-original unit-original) adj-total)]
        (.divide total-cost qty-original
                 4 java.math.RoundingMode/HALF_EVEN)))))

(defn available-layers
  "All layers with positive remaining quantity for the given
   (book, item) pair. Returns entity-ids ordered by
   `:valuation-layer/received-at` ascending, with layer eid as the
   deterministic tie-breaker (FIFO order). Callers that want LIFO
   order can reverse the result.

   Lot-aware: passing `:lot lot-eid` restricts to layers in that
   lot. nil lot means 'don't filter by lot'.

   Implementation: two datalog queries — one fetches candidate layers
   with their original quantity + received-at; the other aggregates
   consumed quantity per layer. Remaining qty is computed in Clojure
   from the join. This is O(2) DB queries regardless of layer count,
   not O(N) as a naive filter-with-helper would be."
  ([db book item] (available-layers db book item nil))
  ([db book item lot]
   (let [candidate-rows
         (if (some? lot)
           (d/q '[:find ?l ?orig ?received
                  :in $ ?book ?item ?lot
                  :where
                  [?l :valuation-layer/book ?book]
                  [?l :valuation-layer/item ?item]
                  [?l :valuation-layer/lot ?lot]
                  [?l :valuation-layer/qty-original ?orig]
                  [?l :valuation-layer/received-at ?received]]
                db book item lot)
           (d/q '[:find ?l ?orig ?received
                  :in $ ?book ?item
                  :where
                  [?l :valuation-layer/book ?book]
                  [?l :valuation-layer/item ?item]
                  [?l :valuation-layer/qty-original ?orig]
                  [?l :valuation-layer/received-at ?received]]
                db book item))
         consumed-by-layer
         (into {}
               (d/q '[:find ?l (sum ?q)
                      :with ?c
                      :in $ ?book ?item
                      :where
                      [?l :valuation-layer/book ?book]
                      [?l :valuation-layer/item ?item]
                      [?c :layer-consumption/layer ?l]
                      [?c :layer-consumption/qty ?q]]
                    db book item))]
     (->> candidate-rows
          (keep (fn [[layer ^java.math.BigDecimal orig received]]
                  (let [consumed (get consumed-by-layer layer 0M)
                        remaining (.subtract orig ^java.math.BigDecimal consumed)]
                    (when (pos? (.signum remaining))
                      [layer received]))))
          ;; Sort by received-at ascending, with eid as deterministic
          ;; tie-breaker for layers that share the same instant.
          (sort-by (juxt second first))
          (mapv first)))))

(defn on-hand-qty
  "Total quantity on hand for an (book, item) pair. Sum of
   remaining quantities across all layers."
  ^java.math.BigDecimal [db book item]
  (reduce (fn [^java.math.BigDecimal acc layer]
            (.add acc (qty-remaining db layer)))
          0M
          (available-layers db book item)))

(defn on-hand-value
  "Total accounting value on hand: Σ (qty-remaining × current-unit-cost)
   across all layers of the (book, item) pair."
  ^java.math.BigDecimal [db book item]
  (reduce (fn [^java.math.BigDecimal acc layer]
            (let [q ^java.math.BigDecimal (qty-remaining db layer)
                  c ^java.math.BigDecimal (current-unit-cost db layer)]
              (.add acc (.multiply q c))))
          0M
          (available-layers db book item)))

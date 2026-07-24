(ns kontor.provider.valuation
  "Valuation books + layers + consumption + adjustments — ADR-027/028.

   This namespace provides:

   - Bootstrap of the primary valuation book (sibling of
     `kontor.reporting.ledger/install-defaults!` from ADR-021).
   - Entity helpers: resolve books by code; resolve layers by their
     (book, item, lot) coordinates.
   - **View computations** that derive remaining quantity, current
     unit cost, on-hand quantity, and on-hand value from the
     append-only fact log. None of these views are materialized;
     all are computed from datalog on demand.

   These helpers are used by `kontor.provider.costing-provider` (which plans
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
   `:db.unique/identity` on `:kontor.valuation-book/code`."
  {:kontor.valuation-book/code        primary-code
   :kontor.valuation-book/name        "Primary valuation book"
   :kontor.valuation-book/framework   :legal
   :kontor.valuation-book/cost-method :fifo
   :kontor.valuation-book/active      true})

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
         :where [?e :kontor.valuation-book/code ?code]]
       db primary-code))

(defn by-code
  "Resolve a valuation book entity-id by its `:kontor.valuation-book/code`."
  [db code]
  (d/q '[:find ?e .
         :in $ ?code
         :where [?e :kontor.valuation-book/code ?code]]
       db code))

(defn resolve-book
  "Coerce `book-spec` to an entity-id. Accepts:
     - nil       → primary book (or nil if not installed)
     - a string  → looked up by `:kontor.valuation-book/code`
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
;;                    contract that `kontor.reporting.balance` already follows.
;;   :include-states  set of `:kontor.transaction/state` keywords whose
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
                          [?t :kontor.transaction/state ?s]]
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
                     [?c :kontor.layer-consumption/layer ?layer]
                     [?c :kontor.layer-consumption/qty ?q]
                     [?c :kontor.layer-consumption/issue-transaction ?tx]
                     [?c :kontor.layer-consumption/issued-at ?issued]]
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
                     :where [?l :kontor.valuation-layer/qty-original ?q]]
                   db layer-eid)]
     (if orig
       (.subtract ^java.math.BigDecimal orig
                  ^java.math.BigDecimal (qty-consumed db layer-eid opts))
       0M))))

(defn value-consumed
  "Total BOOK VALUE drawn out of `layer-eid`:
     Σ (qty × :kontor.layer-consumption/unit-cost-at-consumption)
   over the consumption events included under the opts filter.

   This is deliberately NOT `qty-consumed × the layer's own unit cost`. The
   costing provider decides what a consumption is WORTH, and only FIFO/LIFO/
   FEFO stamp it at the drawn layer's cost. Weighted-average stamps the
   running average and standard cost stamps the standard — in both cases the
   value the GL relieves differs from the layer's acquisition cost, and this
   is the number that matches the GL. note 198 R3-INV-4."
  (^java.math.BigDecimal [db layer-eid] (value-consumed db layer-eid {}))
  (^java.math.BigDecimal [db layer-eid {:keys [as-of-valid include-states]
                                        :or {include-states default-include-states}}]
   (let [rows (d/q '[:find ?q ?u ?tx ?issued
                     :in $ ?layer
                     :where
                     [?c :kontor.layer-consumption/layer ?layer]
                     [?c :kontor.layer-consumption/qty ?q]
                     [?c :kontor.layer-consumption/unit-cost-at-consumption ?u]
                     [?c :kontor.layer-consumption/issue-transaction ?tx]
                     [?c :kontor.layer-consumption/issued-at ?issued]]
                   db layer-eid)]
     (reduce (fn [^java.math.BigDecimal acc
                  [^java.math.BigDecimal q ^java.math.BigDecimal u tx issued]]
               (if (event-included? db tx include-states issued as-of-valid)
                 (.add acc (.multiply q u))
                 acc))
             0M
             rows))))

(defn adjustment-total
  "Sum of `:kontor.layer-adjustment/amount` for the given layer, scoped by opts."
  (^java.math.BigDecimal [db layer-eid] (adjustment-total db layer-eid {}))
  (^java.math.BigDecimal [db layer-eid {:keys [as-of-valid include-states]
                                        :or {include-states default-include-states}}]
   (let [rows (d/q '[:find ?a ?tx ?applied
                     :in $ ?l
                     :where
                     [?adj :kontor.layer-adjustment/layer ?l]
                     [?adj :kontor.layer-adjustment/amount ?a]
                     [?adj :kontor.layer-adjustment/origin-transaction ?tx]
                     [?adj :kontor.layer-adjustment/applied-at ?applied]]
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
                        [:kontor.valuation-layer/qty-original
                         :kontor.valuation-layer/unit-cost-original]
                        layer-eid)
         qty-original  ^java.math.BigDecimal (:kontor.valuation-layer/qty-original pulled)
         unit-original ^java.math.BigDecimal (:kontor.valuation-layer/unit-cost-original pulled)
         adj-total     ^java.math.BigDecimal (adjustment-total db layer-eid opts)]
     (if (or (nil? qty-original) (zero? (.signum qty-original)))
       0M
       (let [total-cost (.add (.multiply qty-original unit-original) adj-total)]
         (.divide total-cost qty-original
                  4 java.math.RoundingMode/HALF_EVEN))))))

(defn- layer-expires-at
  "The `:kontor.lot/expires-at` of a layer's lot, or nil. Used by the
   `:order-by :expires-at` (FEFO) layer ordering."
  ^java.util.Date [db layer-eid]
  (:kontor.lot/expires-at
   (:kontor.valuation-layer/lot
    (d/pull db [{:kontor.valuation-layer/lot [:kontor.lot/expires-at]}] layer-eid))))

(defn available-layers
  "All layers with positive remaining quantity for the given
   (book, item) pair, scoped by opts. Returns entity-ids ordered by
   `:kontor.valuation-layer/received-at` ascending, with layer eid as the
   deterministic tie-breaker (FIFO order).

   Lot-aware: passing a non-nil `lot` restricts to layers in that lot.

   Bitemporal: `:as-of-valid` excludes layers received after the
   cursor AND adjusts consumption sums to only count consumptions
   issued at-or-before the cursor.

   Cancelled-transaction filter: layers whose origin transaction's
   `:kontor.transaction/state` is NOT in `:include-states` are excluded.
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
                  [?l :kontor.valuation-layer/book ?book]
                  [?l :kontor.valuation-layer/item ?item]
                  [?l :kontor.valuation-layer/lot ?lot]
                  [?l :kontor.valuation-layer/qty-original ?orig]
                  [?l :kontor.valuation-layer/received-at ?received]
                  [?l :kontor.valuation-layer/origin-transaction ?tx]]
                db book item lot)
           (d/q '[:find ?l ?orig ?received ?tx
                  :in $ ?book ?item
                  :where
                  [?l :kontor.valuation-layer/book ?book]
                  [?l :kontor.valuation-layer/item ?item]
                  [?l :kontor.valuation-layer/qty-original ?orig]
                  [?l :kontor.valuation-layer/received-at ?received]
                  [?l :kontor.valuation-layer/origin-transaction ?tx]]
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

(defn all-layers
  "Every layer of the (book, item) pair that passes the state + valid-time
   filter, INCLUDING layers already drawn down to zero, ordered FIFO.

   `available-layers` keeps only layers with positive remaining quantity,
   which is right for deciding what to draw from next but wrong for
   valuation: under weighted-average a fully-drained layer can still carry a
   non-zero residual VALUE (its units left at the running average, not at
   what they cost), and dropping it silently loses that value."
  ([db book item] (all-layers db book item nil {}))
  ([db book item lot {:keys [as-of-valid include-states]
                      :or {include-states default-include-states}}]
   (->> (if (some? lot)
          (d/q '[:find ?l ?received ?tx
                 :in $ ?book ?item ?lot
                 :where
                 [?l :kontor.valuation-layer/book ?book]
                 [?l :kontor.valuation-layer/item ?item]
                 [?l :kontor.valuation-layer/lot ?lot]
                 [?l :kontor.valuation-layer/received-at ?received]
                 [?l :kontor.valuation-layer/origin-transaction ?tx]]
               db book item lot)
          (d/q '[:find ?l ?received ?tx
                 :in $ ?book ?item
                 :where
                 [?l :kontor.valuation-layer/book ?book]
                 [?l :kontor.valuation-layer/item ?item]
                 [?l :kontor.valuation-layer/received-at ?received]
                 [?l :kontor.valuation-layer/origin-transaction ?tx]]
               db book item))
        (keep (fn [[layer received tx]]
                (when (event-included? db tx include-states received as-of-valid)
                  [layer received])))
        (sort-by (juxt second first))
        (mapv first))))

(defn layer-value-remaining
  "The book value still sitting on `layer-eid`:

     (qty-original × unit-cost-original + Σ adjustments) − Σ value-consumed

   i.e. what came in, plus what was capitalised onto it, minus what the GL
   already relieved. Under FIFO this equals `qty-remaining × current-unit-cost`
   because consumption is stamped at the layer's own cost; under
   weighted-average or standard cost it does not, and THIS is the figure that
   ties to the GL inventory account."
  (^java.math.BigDecimal [db layer-eid] (layer-value-remaining db layer-eid {}))
  (^java.math.BigDecimal [db layer-eid opts]
   (let [pulled (d/pull db [:kontor.valuation-layer/qty-original
                            :kontor.valuation-layer/unit-cost-original]
                        layer-eid)
         q ^java.math.BigDecimal (:kontor.valuation-layer/qty-original pulled)
         u ^java.math.BigDecimal (:kontor.valuation-layer/unit-cost-original pulled)]
     (if (or (nil? q) (nil? u))
       0M
       (-> (.multiply q u)
           (.add ^java.math.BigDecimal (adjustment-total db layer-eid opts))
           (.subtract ^java.math.BigDecimal (value-consumed db layer-eid opts)))))))

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
  "Total accounting value on hand: Σ `layer-value-remaining` over every layer
   of the (book, item) pair.

   This ties to the GL inventory account by construction, for EVERY cost
   method: what a receipt debits is `qty × unit-cost` (the layer's opening
   value), what an adjustment debits is the adjustment amount, and what an
   issue credits is `qty × unit-cost-at-consumption` — exactly the three terms
   `layer-value-remaining` nets.

   It previously summed `qty-remaining × current-unit-cost` over layers with
   stock left, which silently disagreed with the GL under weighted-average:
   consumption was RELIEVED at the running average but the layer kept its
   original cost, so the average premium on the issued units stayed in the
   subledger and over-valued the remaining stock. That contradicted the
   inventory module's own headline guarantee that the physical and financial
   views cannot drift. note 198 R3-INV-4.

   Under FIFO/LIFO/FEFO the per-layer residual is still the intuitive
   `qty-remaining × unit cost`. Under weighted average it is not: the per-layer
   split of the book's value is an artifact of which layer happened to be
   drained, and only the book-level total is meaningful. Odoo takes the same
   position — it keeps no persistent per-layer cost under AVCO at all
   (stock_account/models/stock_valuation_layer.py `_run_avco`)."
  (^java.math.BigDecimal [db book item] (on-hand-value db book item {}))
  (^java.math.BigDecimal [db book item opts]
   (reduce (fn [^java.math.BigDecimal acc layer]
             (.add acc ^java.math.BigDecimal (layer-value-remaining db layer opts)))
           0M
           (all-layers db book item nil opts))))

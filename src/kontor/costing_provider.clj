(ns kontor.costing-provider
  "CostingProvider protocol + kernel-shipped implementations — ADR-029.

   Direct sibling of `kontor.tax-rate-provider`. The kernel ships four
   methods: FIFO, LIFO, Weighted Average, Standard Cost. Modules
   extend the protocol for jurisdiction-specific variants
   (Anglo-Saxon FIFO, Continental immediate-expense, lot-isolated
   FIFO, etc.). Consumers select the impl via the valuation book's
   `:kontor.valuation-book/cost-method` keyword or pass an instance directly.

   Each impl computes how a stock movement's cost is composed; the
   posting builder (`kontor.posting/plan-stock-move`, ADR-030) takes
   the result and produces a balanced kernel transaction."
  (:require [kontor.valuation :as valuation]))

(defn- view-opts
  "Build the opts map passed to kontor.valuation view helpers from a
   request. Currently extracts :as-of-valid + :include-states so the
   layer queries honor the same bitemporal contract as
   kontor.balance / kontor.trial (ADR-008)."
  [request]
  (cond-> {}
    (contains? request :as-of-valid)
    (assoc :as-of-valid (:as-of-valid request))
    (contains? request :include-states)
    (assoc :include-states (:include-states request))))

;; ============================================================================
;; Protocol
;; ============================================================================

(defprotocol CostingProvider
  "Pluggable cost engine for inventory valuation.

   `plan-consumption` is called for outbound moves. It returns the
   layers to draw from and the unit cost to apply to each.

   `plan-receipt` is called for inbound moves. Most impls echo the
   input; standard cost overrides unit cost and emits a variance."

  (plan-consumption
    [provider db request]
    "Outbound: decide which layers to consume.

     `request` is a map containing at minimum:
       :book           <ent>     valuation-book entity-id
       :item           <ent>     item entity-id (generic ref)
       :qty            <bigdec>  quantity being issued
       :lot            <ent?>    optional lot restriction
       :as-of-valid    <Date?>   bitemporal valid-time cursor.
                                  Layers received after this and
                                  consumptions issued after this are
                                  filtered out. Defaults to nil = no
                                  upper bound.
       :include-states <set?>    transaction-states that count as
                                  'real'. Defaults to
                                  `#{:posted :draft :pending-attestation}`
                                  (everything except :cancelled).

     For tx-time queries, pass `(d/as-of db instant)` as the `db`
     argument — same convention as the rest of the kernel.

     Returns:
       {:consumptions [{:layer eid :qty bd :unit-cost bd} ...]
        :variance     bigdec?       ; signed; positive = over-cost
        :extra-postings [...]?}     ; rare; e.g. price variance line")

  (plan-receipt
    [provider db request]
    "Inbound: decide the layer to create.

     `request` is a map containing at minimum:
       :book       <ent>     valuation-book entity-id
       :item       <ent>     item entity-id
       :qty        <bigdec>  quantity received
       :unit-cost  <bigdec>  invoice / received unit cost
       :commodity  <ent>     cost commodity
       :lot        <ent?>    optional lot ref

     Returns:
       {:layer-data {:qty bd :unit-cost bd :commodity ent :lot ent?
                     :item ent :book ent}
        :variance     bigdec?
        :extra-postings [...]?}"))

;; ============================================================================
;; FIFO
;; ============================================================================

(defrecord FIFOCostingProvider []
  CostingProvider

  (plan-consumption [_ db {:keys [book item qty lot] :as request}]
    (let [opts   (view-opts request)
          layers (valuation/available-layers db book item lot opts)]
      (loop [remaining ^java.math.BigDecimal qty
             [layer & more] layers
             consumptions  []]
        (cond
          (zero? (.signum remaining))
          {:consumptions consumptions}

          (nil? layer)
          ;; Out of stock — return whatever we managed, plus an
          ;; `:underflow` flag so the caller can decide whether to
          ;; allow negative stock or refuse.
          {:consumptions consumptions
           :underflow    remaining}

          :else
          (let [avail ^java.math.BigDecimal (valuation/qty-remaining db layer opts)
                take  (if (<= (.compareTo remaining avail) 0) remaining avail)
                unit  ^java.math.BigDecimal (valuation/current-unit-cost db layer opts)]
            (recur (.subtract remaining take)
                   more
                   (conj consumptions
                         {:layer     layer
                          :qty       take
                          :unit-cost unit})))))))

  (plan-receipt [_ _db {:keys [book item qty unit-cost commodity lot]}]
    {:layer-data (cond-> {:book      book
                          :item      item
                          :qty       qty
                          :unit-cost unit-cost
                          :commodity commodity}
                   lot (assoc :lot lot))}))

(defn make-fifo-provider [] (->FIFOCostingProvider))

;; ============================================================================
;; LIFO  — same logic, layers reversed
;; ============================================================================

(defrecord LIFOCostingProvider []
  CostingProvider

  (plan-consumption [_ db {:keys [book item qty lot] :as request}]
    (let [opts   (view-opts request)
          layers (reverse (valuation/available-layers db book item lot opts))]
      (loop [remaining ^java.math.BigDecimal qty
             [layer & more] layers
             consumptions  []]
        (cond
          (zero? (.signum remaining))
          {:consumptions consumptions}

          (nil? layer)
          {:consumptions consumptions :underflow remaining}

          :else
          (let [avail ^java.math.BigDecimal (valuation/qty-remaining db layer opts)
                take  (if (<= (.compareTo remaining avail) 0) remaining avail)
                unit  ^java.math.BigDecimal (valuation/current-unit-cost db layer opts)]
            (recur (.subtract remaining take)
                   more
                   (conj consumptions
                         {:layer     layer
                          :qty       take
                          :unit-cost unit})))))))

  (plan-receipt [_ _db request]
    ;; LIFO receipt is identical to FIFO receipt — a new layer.
    {:layer-data (select-keys request
                              [:book :item :qty :unit-cost
                               :commodity :lot])}))

(defn make-lifo-provider [] (->LIFOCostingProvider))

;; ============================================================================
;; Weighted Average
;; ============================================================================

(defn- weighted-avg-unit-cost
  "Total value / total quantity across all available layers for
   (book, item) at the bitemporal cursor. Returns 0M when no layers."
  ^java.math.BigDecimal [db book item opts]
  (let [layers (valuation/available-layers db book item nil opts)]
    (if (empty? layers)
      0M
      (let [[total-qty total-val]
            (reduce
             (fn [[^java.math.BigDecimal q ^java.math.BigDecimal v] layer]
               (let [lq ^java.math.BigDecimal (valuation/qty-remaining db layer opts)
                     lc ^java.math.BigDecimal (valuation/current-unit-cost db layer opts)]
                 [(.add q lq)
                  (.add v (.multiply lq lc))]))
             [0M 0M]
             layers)]
        (if (zero? (.signum total-qty))
          0M
          (.divide total-val total-qty
                   4 java.math.RoundingMode/HALF_EVEN))))))

(defrecord WeightedAverageProvider []
  CostingProvider

  (plan-consumption [_ db {:keys [book item qty lot] :as request}]
    ;; Weighted average distributes the consumption across all
    ;; available layers proportionally to their remaining quantity.
    ;; Each consumption row gets the SAME unit-cost (the weighted
    ;; average), regardless of which layer.
    (let [opts      (view-opts request)
          unit-cost (weighted-avg-unit-cost db book item opts)
          layers    (valuation/available-layers db book item lot opts)
          total-avail
          (reduce (fn [^java.math.BigDecimal acc layer]
                    (.add acc ^java.math.BigDecimal
                          (valuation/qty-remaining db layer opts)))
                  0M
                  layers)]
      (cond
        (empty? layers)
        {:consumptions [] :underflow qty}

        (zero? (.signum total-avail))
        {:consumptions [] :underflow qty}

        :else
        (loop [remaining ^java.math.BigDecimal qty
               [layer & more] layers
               consumptions  []]
          (cond
            (zero? (.signum remaining))
            {:consumptions consumptions}

            (nil? layer)
            {:consumptions consumptions :underflow remaining}

            :else
            (let [avail ^java.math.BigDecimal (valuation/qty-remaining db layer opts)
                  take  (if (<= (.compareTo remaining avail) 0) remaining avail)]
              (recur (.subtract remaining take)
                     more
                     (conj consumptions
                           {:layer     layer
                            :qty       take
                            :unit-cost unit-cost}))))))))

  (plan-receipt [_ _db request]
    {:layer-data (select-keys request
                              [:book :item :qty :unit-cost
                               :commodity :lot])}))

(defn make-weighted-average-provider [] (->WeightedAverageProvider))

;; ============================================================================
;; Standard Cost
;; ============================================================================
;;
;; A standard-cost provider needs a *standard* unit cost per (book,
;; item) — typically maintained on the item master or a per-item
;; standard-cost table that consumer modules ship. For the kernel
;; reference impl we accept a `standard-cost-fn` at construction time:
;;
;;   (fn [db book item] → bigdec)
;;
;; When the standard cost differs from the actual receipt cost, the
;; provider returns a :variance value (positive = under-cost,
;; i.e. actual > standard, so a debit to PPV) plus a structural hint
;; that the posting builder uses to route the variance to the
;; configured price-variance account.

(defrecord StandardCostProvider [standard-cost-fn]
  CostingProvider

  (plan-consumption [_ db {:keys [book item qty lot] :as request}]
    ;; Standard-cost issues always use the standard unit cost —
    ;; consumption from any layer carries the same value. Layers
    ;; still need to be drawn from (to keep on-hand correct), so we
    ;; pick FIFO order for the consumption events but stamp every
    ;; one with the standard cost.
    ;;
    ;; Note: layer order is FIFO by `:received-at`. This matches SAP
    ;; S/4HANA Material Ledger's standard-cost on-hand bookkeeping
    ;; convention (layers are drained in receipt order even when each
    ;; consumption is stamped at the standard cost).
    (let [opts     (view-opts request)
          std-cost (standard-cost-fn db book item)
          layers   (valuation/available-layers db book item lot opts)]
      (loop [remaining ^java.math.BigDecimal qty
             [layer & more] layers
             consumptions  []]
        (cond
          (zero? (.signum remaining))
          {:consumptions consumptions}

          (nil? layer)
          {:consumptions consumptions :underflow remaining}

          :else
          (let [avail ^java.math.BigDecimal (valuation/qty-remaining db layer)
                take  (if (<= (.compareTo remaining avail) 0) remaining avail)]
            (recur (.subtract remaining take)
                   more
                   (conj consumptions
                         {:layer     layer
                          :qty       take
                          :unit-cost std-cost})))))))

  (plan-receipt [_ db {:keys [book item qty unit-cost] :as request}]
    ;; Receipt at standard: the LAYER carries the standard unit cost,
    ;; not the actual. The difference between actual (invoice) and
    ;; standard is a price variance for the receipt.
    (let [std-cost (standard-cost-fn db book item)
          variance (.multiply
                    (.subtract ^java.math.BigDecimal unit-cost
                               ^java.math.BigDecimal std-cost)
                    ^java.math.BigDecimal qty)]
      {:layer-data (-> request
                       (select-keys [:book :item :qty :commodity :lot])
                       (assoc :unit-cost std-cost))
       :variance   variance})))

(defn make-standard-cost-provider
  "Construct a StandardCostProvider given a `standard-cost-fn`
   (signature: `(fn [db book item] → bigdec)`)."
  [standard-cost-fn]
  (->StandardCostProvider standard-cost-fn))

;; ============================================================================
;; Resolution by cost-method keyword
;; ============================================================================

(defn provider-for
  "Pick a kernel-shipped provider implementation for a
   `:kontor.valuation-book/cost-method` keyword. Standard cost requires
   the caller to supply `standard-cost-fn`."
  ([method] (provider-for method nil))
  ([method standard-cost-fn]
   (case method
     :fifo     (make-fifo-provider)
     :lifo     (make-lifo-provider)
     :avg      (make-weighted-average-provider)
     :standard (make-standard-cost-provider
                (or standard-cost-fn
                    (throw (ex-info ":standard cost-method requires a standard-cost-fn"
                                    {:method method}))))
     (throw (ex-info "Unknown cost-method"
                     {:method    method
                      :supported #{:fifo :lifo :avg :standard}})))))

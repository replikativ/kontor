(ns kontor.inventory.fefo-costing-provider
  "FEFO (first-expiry-first-out) CostingProvider — ADR-060.

   A companion-shipped `CostingProvider` impl — ADR-029's protocol is
   pluggable and explicitly invites module-shipped variants. FEFO is
   FIFO with cost layers drawn in lot-expiry order rather than
   receipt order: the perishable-goods method (food, pharma,
   chemicals). It delegates the ordering to
   `kontor.provider.valuation/available-layers`' `:order-by :expires-at` and
   otherwise runs the same draw loop as the kernel's
   `FIFOCostingProvider`."
  (:require [kontor.provider.costing-provider :as costing]
            [kontor.provider.valuation :as valuation])
  (:import [java.math BigDecimal]))

(defn- view-opts
  "Layer-query opts from a consumption request — always FEFO-ordered."
  [request]
  (cond-> {:order-by :expires-at}
    (contains? request :as-of-valid)
    (assoc :as-of-valid (:as-of-valid request))
    (contains? request :include-states)
    (assoc :include-states (:include-states request))))

(defrecord FefoCostingProvider []
  costing/CostingProvider

  (plan-consumption [_ db {:keys [book item lot] :as request}]
    (let [opts   (view-opts request)
          layers (valuation/available-layers db book item lot opts)]
      (loop [remaining ^BigDecimal (:qty request)
             [layer & more] layers
             consumptions  []]
        (cond
          (zero? (.signum remaining))
          {:consumptions consumptions}

          (nil? layer)
          {:consumptions consumptions :underflow remaining}

          :else
          (let [avail ^BigDecimal (valuation/qty-remaining db layer opts)
                take  (if (<= (.compareTo remaining avail) 0) remaining avail)
                unit  ^BigDecimal (valuation/current-unit-cost db layer opts)]
            (recur (.subtract remaining take)
                   more
                   (conj consumptions
                         {:layer layer :qty take :unit-cost unit})))))))

  (plan-receipt [_ _db request]
    ;; FEFO receipt is a plain new layer, like FIFO — the expiry
    ;; ordering only matters on consumption.
    {:layer-data (select-keys request
                              [:book :item :qty :unit-cost :commodity :lot])}))

(defn make-fefo-provider
  "Construct a `FefoCostingProvider`. Register it like any other
   `CostingProvider`, or pass the instance directly to a stock-move
   builder / the kontor-inventory ops."
  []
  (->FefoCostingProvider))

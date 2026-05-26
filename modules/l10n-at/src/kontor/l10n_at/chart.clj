(ns kontor.l10n-at.chart
  "Austrian Einheitskontenrahmen loader.

   Mirrors the DE chart-installer pattern (see ../l10n-de/chart.clj).
   Reads `kontenrahmen.edn`, materializes :account-tag entities for
   the UVA field codes (022/029/006/057/066/011/021), creates
   accounts with :kontor.account/code + :kontor.account/tags."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [datahike.api :as d]))

(defn load-chart []
  (-> "kontor/l10n_at/kontenrahmen.edn"
      io/resource
      slurp
      edn/read-string))

(defn- distinct-tags [chart]
  (->> chart (mapcat :tags) distinct vec))

(defn- tag-tx-data [tags]
  (mapv (fn [tag]
          {:kontor.account-tag/name (name tag)
           :kontor.account-tag/country-code "AT"
           :kontor.account-tag/applicability :account})
        tags))

(defn- ensure-eur []
  {:kontor.commodity/symbol "EUR"
   :kontor.commodity/name "Euro"
   :kontor.commodity/precision 2
   :kontor.commodity/iso-4217 "EUR"})

(defn- account-tx-entry
  [{:keys [code path type name reconcilable? tags]}]
  (cond-> {:kontor.account/path        path
           :kontor.account/code        code
           :kontor.account/name        name
           :kontor.account/type        type
           :kontor.account/active      true
           :kontor.account/commodity   [:kontor.commodity/symbol "EUR"]
           :kontor.account/reconcilable (boolean reconcilable?)}
    (seq tags)
    (assoc :kontor.account/tags
           (mapv (fn [t] [:kontor.account-tag/name (clojure.core/name t)]) tags))))

(defn install!
  ([conn] (install! conn (load-chart)))
  ([conn chart]
   (d/transact conn [(ensure-eur)])
   (d/transact conn (tag-tx-data (distinct-tags chart)))
   (d/transact conn (mapv account-tx-entry chart))))

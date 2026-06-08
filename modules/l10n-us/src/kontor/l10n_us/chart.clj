(ns kontor.l10n-us.chart
  "US chart loader. USD commodity. Same shape as DE/AT/FR/CA."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [datahike.api :as d]))

(defn load-chart []
  (-> "kontor/l10n_us/chart.edn" io/resource slurp edn/read-string))

(defn- distinct-tags [chart]
  (->> chart (mapcat :tags) distinct vec))

(defn- tag-tx [tags]
  (mapv (fn [t] {:kontor.account-tag/name (name t)
                 :kontor.account-tag/country-code "US"
                 :kontor.account-tag/applicability :account})
        tags))

(defn- ensure-usd []
  {:kontor.commodity/symbol "USD" :kontor.commodity/name "US Dollar"
   :kontor.commodity/precision 2 :kontor.commodity/iso-4217 "USD"})

(defn- account-tx
  [{:keys [code path type name reconcilable? tags]}]
  (cond-> {:kontor.account/path path :kontor.account/code code :kontor.account/name name
           :kontor.account/type type :kontor.account/active true
           :kontor.account/commodity [:kontor.commodity/symbol "USD"]
           :kontor.account/reconcilable (boolean reconcilable?)}
    (seq tags)
    (assoc :kontor.account/tags
           (mapv (fn [t] [:kontor.account-tag/name (clojure.core/name t)]) tags))))

(defn install!
  ([conn] (install! conn (load-chart)))
  ([conn chart]
   (d/transact conn [(ensure-usd)])
   (d/transact conn (tag-tx (distinct-tags chart)))
   (d/transact conn (mapv account-tx chart))))

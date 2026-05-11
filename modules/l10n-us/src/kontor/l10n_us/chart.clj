(ns datahike-accounting.l10n-us.chart
  "QBO-style US chart loader. USD commodity. Same shape as DE/AT/FR/CA."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [datahike.api :as d]))

(defn load-chart []
  (-> "datahike_accounting/l10n_us/chart.edn" io/resource slurp edn/read-string))

(defn- distinct-tags [chart]
  (->> chart (mapcat :tags) distinct vec))

(defn- tag-tx [tags]
  (mapv (fn [t] {:account-tag/name (name t)
                 :account-tag/country-code "US"
                 :account-tag/applicability :account})
        tags))

(defn- ensure-usd []
  {:commodity/symbol "USD" :commodity/name "US Dollar"
   :commodity/precision 2 :commodity/iso-4217 "USD"})

(defn- account-tx
  [{:keys [code path type name reconcilable? tags]}]
  (cond-> {:account/path path :account/code code :account/name name
           :account/type type :account/active true
           :account/commodity [:commodity/symbol "USD"]
           :account/reconcilable (boolean reconcilable?)}
    (seq tags)
    (assoc :account/tags
           (mapv (fn [t] [:account-tag/name (clojure.core/name t)]) tags))))

(defn install!
  ([conn] (install! conn (load-chart)))
  ([conn chart]
   (d/transact conn [(ensure-usd)])
   (d/transact conn (tag-tx (distinct-tags chart)))
   (d/transact conn (mapv account-tx chart))))

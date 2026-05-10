(ns datahike-accounting.l10n-ca.chart
  "Canadian chart loader. Same shape as DE/AT/FR but emits CAD as
   the default commodity instead of EUR."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [datahike.api :as d]))

(defn load-chart []
  (-> "datahike_accounting/l10n_ca/chart.edn" io/resource slurp edn/read-string))

(defn- distinct-tags [chart]
  (->> chart (mapcat :tags) distinct vec))

(defn- tag-tx [tags]
  (mapv (fn [t] {:account-tag/name (name t)
                 :account-tag/country-code "CA"
                 :account-tag/applicability :account})
        tags))

(defn- ensure-cad []
  {:commodity/symbol "CAD" :commodity/name "Canadian Dollar"
   :commodity/precision 2 :commodity/iso-4217 "CAD"})

(defn- account-tx
  [{:keys [code path type name reconcilable? tags]}]
  (cond-> {:account/path path :account/code code :account/name name
           :account/type type :account/active true
           :account/commodity [:commodity/symbol "CAD"]
           :account/reconcilable (boolean reconcilable?)}
    (seq tags)
    (assoc :account/tags
           (mapv (fn [t] [:account-tag/name (clojure.core/name t)]) tags))))

(defn install!
  ([conn] (install! conn (load-chart)))
  ([conn chart]
   (d/transact conn [(ensure-cad)])
   (d/transact conn (tag-tx (distinct-tags chart)))
   (d/transact conn (mapv account-tx chart))))

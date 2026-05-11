(ns kontor.l10n-fr.chart
  "PCG (Plan Comptable Général) loader. Mirrors the DE/AT pattern."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [datahike.api :as d]))

(defn load-chart []
  (-> "kontor/l10n_fr/pcg.edn" io/resource slurp edn/read-string))

(defn- distinct-tags [chart]
  (->> chart (mapcat :tags) distinct vec))

(defn- tag-tx [tags]
  (mapv (fn [t] {:account-tag/name (name t)
                 :account-tag/country-code "FR"
                 :account-tag/applicability :account})
        tags))

(defn- ensure-eur []
  {:commodity/symbol "EUR" :commodity/name "Euro"
   :commodity/precision 2 :commodity/iso-4217 "EUR"})

(defn- account-tx
  [{:keys [code path type name reconcilable? tags]}]
  (cond-> {:account/path path :account/code code :account/name name
           :account/type type :account/active true
           :account/commodity [:commodity/symbol "EUR"]
           :account/reconcilable (boolean reconcilable?)}
    (seq tags)
    (assoc :account/tags
           (mapv (fn [t] [:account-tag/name (clojure.core/name t)]) tags))))

(defn install!
  ([conn] (install! conn (load-chart)))
  ([conn chart]
   (d/transact conn [(ensure-eur)])
   (d/transact conn (tag-tx (distinct-tags chart)))
   (d/transact conn (mapv account-tx chart))))

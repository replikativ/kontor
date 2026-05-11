(ns kontor.l10n-au.chart
  "Australian chart-of-accounts loader. Mirrors the JP / CA pattern.

   The ATO does not mandate a CoA; this is a starter. Tags map each
   account to its BAS label so the BAS report engine aggregates
   correctly without re-tagging downstream."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [datahike.api :as d]))

(defn load-chart []
  (-> "kontor/l10n_au/chart.edn" io/resource slurp edn/read-string))

(defn- distinct-tags [chart]
  (->> chart (mapcat :tags) distinct vec))

(defn- tag-tx [tags]
  (mapv (fn [t] {:account-tag/name (name t)
                 :account-tag/country-code "AU"
                 :account-tag/applicability :account})
        tags))

(defn- ensure-aud []
  {:commodity/symbol "AUD"
   :commodity/name "Australian Dollar"
   :commodity/precision 2
   :commodity/iso-4217 "AUD"})

(defn- account-tx
  [{:keys [code path type name reconcilable? tags]}]
  (cond-> {:account/path path :account/code code :account/name name
           :account/type type :account/active true
           :account/commodity [:commodity/symbol "AUD"]
           :account/reconcilable (boolean reconcilable?)}
    (seq tags)
    (assoc :account/tags
           (mapv (fn [t] [:account-tag/name (clojure.core/name t)]) tags))))

(defn install!
  ([conn] (install! conn (load-chart)))
  ([conn chart]
   (d/transact conn [(ensure-aud)])
   (d/transact conn (tag-tx (distinct-tags chart)))
   (d/transact conn (mapv account-tx chart))))

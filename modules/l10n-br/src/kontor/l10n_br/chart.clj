(ns kontor.l10n-br.chart
  "Brazilian chart-of-accounts loader.

   Loads a Plano de Contas Referencial-aligned starter (~60 accounts)
   into a datahike connection. Each account carries an
   `:account/external-codes` mapping to its RFB Plano Referencial
   code (per ADR-019) so ECF filing's De/Para (from/to) mapping is
   already in place.

   Commodity: BRL (Brazilian Real), precision 2."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [datahike.api :as d]))

(defn load-chart []
  (-> "kontor/l10n_br/chart.edn" io/resource slurp edn/read-string))

(defn- distinct-tags [chart]
  (->> chart (mapcat :tags) distinct vec))

(defn- tag-tx [tags]
  (mapv (fn [t] {:account-tag/name (name t)
                 :account-tag/country-code "BR"
                 :account-tag/applicability :account})
        tags))

(defn- ensure-brl []
  {:commodity/symbol "BRL"
   :commodity/name "Brazilian Real"
   :commodity/precision 2
   :commodity/iso-4217 "BRL"})

(defn- account-tx
  [{:keys [code path type name reconcilable? tags]}]
  (cond-> {:account/path path :account/code code :account/name name
           :account/type type :account/active true
           :account/commodity [:commodity/symbol "BRL"]
           :account/reconcilable (boolean reconcilable?)}
    (seq tags)
    (assoc :account/tags
           (mapv (fn [t] [:account-tag/name (clojure.core/name t)]) tags))))

(defn- external-code-tx [chart]
  (vec
   (mapcat
    (fn [{:keys [path external-codes]}]
      (when (and path external-codes)
        (map (fn [[regulator code]]
               {:account-code/account   [:account/path path]
                :account-code/regulator regulator
                :account-code/code      code})
             external-codes)))
    chart)))

(defn install!
  "Install the BR chart. Idempotent."
  ([conn] (install! conn (load-chart)))
  ([conn chart]
   (d/transact conn [(ensure-brl)])
   (d/transact conn (tag-tx (distinct-tags chart)))
   (d/transact conn (mapv account-tx chart))
   (let [codes (external-code-tx chart)]
     (when (seq codes)
       (d/transact conn codes)))))

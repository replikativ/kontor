(ns kontor.l10n-br.chart
  "Brazilian chart-of-accounts loader.

   Loads a Plano de Contas Referencial-aligned starter (~60 accounts)
   into a datahike connection. Each account carries an
   `:kontor.account/external-codes` mapping to its RFB Plano Referencial
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
  (mapv (fn [t] {:kontor.account-tag/name (name t)
                 :kontor.account-tag/country-code "BR"
                 :kontor.account-tag/applicability :account})
        tags))

(defn- ensure-brl []
  {:kontor.commodity/symbol "BRL"
   :kontor.commodity/name "Brazilian Real"
   :kontor.commodity/precision 2
   :kontor.commodity/iso-4217 "BRL"})

(defn- account-tx
  [{:keys [code path type name reconcilable? tags]}]
  (cond-> {:kontor.account/path path :kontor.account/code code :kontor.account/name name
           :kontor.account/type type :kontor.account/active true
           :kontor.account/commodity [:kontor.commodity/symbol "BRL"]
           :kontor.account/reconcilable (boolean reconcilable?)}
    (seq tags)
    (assoc :kontor.account/tags
           (mapv (fn [t] [:kontor.account-tag/name (clojure.core/name t)]) tags))))

(defn- external-code-tx [chart]
  (vec
   (mapcat
    (fn [{:keys [path external-codes]}]
      (when (and path external-codes)
        (map (fn [[regulator code]]
               {:kontor.account-code/account   [:kontor.account/path path]
                :kontor.account-code/regulator regulator
                :kontor.account-code/code      code})
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

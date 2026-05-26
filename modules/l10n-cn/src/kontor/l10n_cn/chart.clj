(ns kontor.l10n-cn.chart
  "Chinese chart-of-accounts loader.

   Loads an ASBE / ASSBE-coded small-business subset into a datahike
   connection. Each account also carries an `:kontor.account/external-codes`
   ref to an `:account-code` entity with `:cn/asbe` as the regulator
   key (per ADR-019), so statutory reports can look up the canonical
   ASBE code without ambiguity.

   Commodity: CNY (Chinese Yuan / 人民币), precision 2."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [datahike.api :as d]))

(defn load-chart []
  (-> "kontor/l10n_cn/chart.edn" io/resource slurp edn/read-string))

(defn- distinct-tags [chart]
  (->> chart (mapcat :tags) distinct vec))

(defn- tag-tx [tags]
  (mapv (fn [t] {:kontor.account-tag/name (name t)
                 :kontor.account-tag/country-code "CN"
                 :kontor.account-tag/applicability :account})
        tags))

(defn- ensure-cny []
  {:kontor.commodity/symbol "CNY"
   :kontor.commodity/name "Chinese Yuan"
   :kontor.commodity/precision 2
   :kontor.commodity/iso-4217 "CNY"})

(defn- account-tx
  [{:keys [code path type name reconcilable? tags]}]
  (cond-> {:kontor.account/path path :kontor.account/code code :kontor.account/name name
           :kontor.account/type type :kontor.account/active true
           :kontor.account/commodity [:kontor.commodity/symbol "CNY"]
           :kontor.account/reconcilable (boolean reconcilable?)}
    (seq tags)
    (assoc :kontor.account/tags
           (mapv (fn [t] [:kontor.account-tag/name (clojure.core/name t)]) tags))))

(defn- external-code-tx
  "For each account that carries :external-codes, emit one
   :account-code entity per (regulator, code) pair."
  [chart]
  (vec
   (mapcat
    (fn [{:keys [path external-codes]}]
      (when (and path external-codes)
        (map (fn [[regulator code]]
               {:kontor.account-code/account   [:kontor.account/path path]
                :kontor.account-code/regulator regulator
                :kontor.account-code/code      code
                :kontor.account-code/note      "ASBE-coded — see ADR-019"})
             external-codes)))
    chart)))

(defn install!
  "Install the CN chart. Idempotent."
  ([conn] (install! conn (load-chart)))
  ([conn chart]
   (d/transact conn [(ensure-cny)])
   (d/transact conn (tag-tx (distinct-tags chart)))
   (d/transact conn (mapv account-tx chart))
   ;; External codes go in a separate tx because they reference the
   ;; accounts created above by their :kontor.account/path identity.
   (let [codes (external-code-tx chart)]
     (when (seq codes)
       (d/transact conn codes)))))

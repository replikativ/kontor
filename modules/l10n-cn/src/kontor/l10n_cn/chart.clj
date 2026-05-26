(ns kontor.l10n-cn.chart
  "Chinese chart-of-accounts loader.

   Loads an ASBE / ASSBE-coded small-business subset into a datahike
   connection. Each account also carries an `:account/external-codes`
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
  (mapv (fn [t] {:account-tag/name (name t)
                 :account-tag/country-code "CN"
                 :account-tag/applicability :account})
        tags))

(defn- ensure-cny []
  {:kontor.commodity/symbol "CNY"
   :kontor.commodity/name "Chinese Yuan"
   :kontor.commodity/precision 2
   :kontor.commodity/iso-4217 "CNY"})

(defn- account-tx
  [{:keys [code path type name reconcilable? tags]}]
  (cond-> {:account/path path :account/code code :account/name name
           :account/type type :account/active true
           :account/commodity [:kontor.commodity/symbol "CNY"]
           :account/reconcilable (boolean reconcilable?)}
    (seq tags)
    (assoc :account/tags
           (mapv (fn [t] [:account-tag/name (clojure.core/name t)]) tags))))

(defn- external-code-tx
  "For each account that carries :external-codes, emit one
   :account-code entity per (regulator, code) pair."
  [chart]
  (vec
   (mapcat
    (fn [{:keys [path external-codes]}]
      (when (and path external-codes)
        (map (fn [[regulator code]]
               {:account-code/account   [:account/path path]
                :account-code/regulator regulator
                :account-code/code      code
                :account-code/note      "ASBE-coded — see ADR-019"})
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
   ;; accounts created above by their :account/path identity.
   (let [codes (external-code-tx chart)]
     (when (seq codes)
       (d/transact conn codes)))))

(ns kontor.l10n-jp.chart
  "Japanese chart-of-accounts loader.

   Loads a J-GAAP-style skeleton CoA into a datahike connection.
   Pattern mirrors `kontor.l10n-ca.chart` and `kontor.l10n-de.chart`.

   Per the Odoo l10n_jp convention (and ours), this chart is a
   starting point that businesses extend; J-GAAP does not mandate a
   specific account list. The included accounts cover the common
   shape needed to support consumption tax (JCT) at both rates and
   the QIS-compliant invoicing.

   Commodity defaults to JPY with precision 0 (yen has no sub-unit;
   ADR-013 precision attribute documents this case)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [datahike.api :as d]))

(defn load-chart
  "Read the bundled JP chart EDN."
  []
  (-> "kontor/l10n_jp/chart.edn" io/resource slurp edn/read-string))

(defn- distinct-tags [chart]
  (->> chart (mapcat :tags) distinct vec))

(defn- tag-tx [tags]
  (mapv (fn [t] {:kontor.account-tag/name (name t)
                 :kontor.account-tag/country-code "JP"
                 :kontor.account-tag/applicability :account})
        tags))

(defn- ensure-jpy []
  {:kontor.commodity/symbol "JPY"
   :kontor.commodity/name "Japanese Yen"
   :kontor.commodity/precision 0
   :kontor.commodity/iso-4217 "JPY"})

(defn- account-tx
  [{:keys [code path type name reconcilable? tags]}]
  (cond-> {:kontor.account/path path :kontor.account/code code :kontor.account/name name
           :kontor.account/type type :kontor.account/active true
           :kontor.account/commodity [:kontor.commodity/symbol "JPY"]
           :kontor.account/reconcilable (boolean reconcilable?)}
    (seq tags)
    (assoc :kontor.account/tags
           (mapv (fn [t] [:kontor.account-tag/name (clojure.core/name t)]) tags))))

(defn install!
  "Install the JP chart into a datahike connection. Idempotent."
  ([conn] (install! conn (load-chart)))
  ([conn chart]
   (d/transact conn [(ensure-jpy)])
   (d/transact conn (tag-tx (distinct-tags chart)))
   (d/transact conn (mapv account-tx chart))))

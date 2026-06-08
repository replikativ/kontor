(ns kontor.payroll-br.chart
  "Installer for the BR payroll account-tag set + (optional) starter
   accounts. Follows the same shape as kontor.l10n-br.chart and
   kontor.payroll-ca.chart — consumers run kontor.l10n-br.chart/install!
   first for the base BR Plano de Contas Referencial, then this for the
   payroll extension.

   Reference: ADR-081 §7 + research.3."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [datahike.api :as d]))

(defn load-starter
  "Load the BR payroll-extension starter chart."
  []
  (-> "kontor/payroll_br/coa_starter.edn" io/resource slurp edn/read-string))

(defn- distinct-tags [chart]
  (->> chart (mapcat :tags) distinct vec))

(defn- tag-tx [tags]
  (mapv (fn [t] {:kontor.account-tag/name (name t)
                 :kontor.account-tag/country-code "BR"
                 :kontor.account-tag/applicability :account})
        tags))

(defn- account-tx
  [{:keys [code path type name reconcilable? tags]}]
  (cond-> {:kontor.account/path path :kontor.account/code code :kontor.account/name name
           :kontor.account/type type :kontor.account/active true
           :kontor.account/commodity [:kontor.commodity/symbol "BRL"]
           :kontor.account/reconcilable (boolean reconcilable?)}
    (seq tags)
    (assoc :kontor.account/tags
           (mapv (fn [t] [:kontor.account-tag/name (clojure.core/name t)]) tags))))

(defn install-tags!
  "Idempotent install of just the :account-tag entities (no accounts).
   Useful when the consumer's chart already exists and only needs the
   payroll tag vocabulary registered."
  ([conn] (install-tags! conn (load-starter)))
  ([conn chart]
   (d/transact conn (tag-tx (distinct-tags chart)))))

(defn install!
  "Idempotent install of payroll tags + the starter chart on top of an
   already-installed l10n-br/chart. Safe to skip the accounts and
   manage them in the consumer's own chart — call `install-tags!`
   instead in that case."
  ([conn] (install! conn (load-starter)))
  ([conn chart]
   (d/transact conn (tag-tx (distinct-tags chart)))
   (d/transact conn (mapv account-tx chart))))

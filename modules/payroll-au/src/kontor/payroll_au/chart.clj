(ns kontor.payroll-au.chart
  "Installer for the AU payroll account-tag set + (optional) starter
   chart. Mirrors `kontor.payroll-ca.chart` — consumers run
   `kontor.l10n-au.chart/install!` first for the base AU chart, then
   this for the payroll extension.

   Reference: ADR-080."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [datahike.api :as d]))

(defn load-starter
  "Load the payroll-extension starter chart."
  []
  (-> "kontor/payroll_au/coa_starter.edn" io/resource slurp edn/read-string))

(defn- distinct-tags [chart]
  (->> chart (mapcat :tags) distinct vec))

(defn- tag-tx [tags]
  (mapv (fn [t] {:kontor.account-tag/name (name t)
                 :kontor.account-tag/country-code "AU"
                 :kontor.account-tag/applicability :account})
        tags))

(defn- account-tx
  [{:keys [code path type name reconcilable? tags]}]
  (cond-> {:kontor.account/path path :kontor.account/code code :kontor.account/name name
           :kontor.account/type type :kontor.account/active true
           :kontor.account/commodity [:kontor.commodity/symbol "AUD"]
           :kontor.account/reconcilable (boolean reconcilable?)}
    (seq tags)
    (assoc :kontor.account/tags
           (mapv (fn [t] [:kontor.account-tag/name (clojure.core/name t)]) tags))))

(defn install-tags!
  "Idempotent install of just the `:account-tag` entities (no accounts).
   Useful when the consumer's chart already exists and only needs the
   payroll tag vocabulary registered."
  ([conn] (install-tags! conn (load-starter)))
  ([conn chart]
   (d/transact conn (tag-tx (distinct-tags chart)))))

(defn install!
  "Idempotent install of payroll tags + the starter chart on top of an
   already-installed `kontor.l10n-au.chart`. Safe to skip the accounts
   and manage them in the consumer's own chart — call `install-tags!`
   instead in that case."
  ([conn] (install! conn (load-starter)))
  ([conn chart]
   (d/transact conn (tag-tx (distinct-tags chart)))
   (d/transact conn (mapv account-tx chart))))

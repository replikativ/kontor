(ns kontor.payroll-fr.chart
  "Installer for the FR payroll account-tag set + (optional) starter
   accounts. Follows the same shape as kontor.l10n-fr.chart but layers
   on top — consumers run kontor.l10n-fr.chart/install! first for the
   PCG base chart, then this for the payroll extension.

   Reference: ADR-079; doc/research/79 §5.3."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [datahike.api :as d]))

(defn load-starter
  "Load the payroll-extension starter chart (ADR-079)."
  []
  (-> "kontor/payroll_fr/coa_starter.edn" io/resource slurp edn/read-string))

(defn- distinct-tags [chart]
  (->> chart (mapcat :tags) distinct vec))

(defn- tag-tx [tags]
  (mapv (fn [t] {:account-tag/name (name t)
                 :account-tag/country-code "FR"
                 :account-tag/applicability :account})
        tags))

(defn- account-tx
  [{:keys [code path type name reconcilable? tags]}]
  (cond-> {:account/path path :account/code code :account/name name
           :account/type type :account/active true
           :account/commodity [:kontor.commodity/symbol "EUR"]
           :account/reconcilable (boolean reconcilable?)}
    (seq tags)
    (assoc :account/tags
           (mapv (fn [t] [:account-tag/name (clojure.core/name t)]) tags))))

(defn install-tags!
  "Idempotent install of just the :account-tag entities (no accounts).
   Useful when the consumer's chart already exists and only needs the
   payroll tag vocabulary registered."
  ([conn] (install-tags! conn (load-starter)))
  ([conn chart]
   (d/transact conn (tag-tx (distinct-tags chart)))))

(defn install!
  "Idempotent install of payroll tags + the starter chart on top of an
   already-installed l10n-fr/chart. Safe to skip the accounts and
   manage them in the consumer's own chart — call `install-tags!`
   instead in that case."
  ([conn] (install! conn (load-starter)))
  ([conn chart]
   (d/transact conn (tag-tx (distinct-tags chart)))
   (d/transact conn (mapv account-tx chart))))

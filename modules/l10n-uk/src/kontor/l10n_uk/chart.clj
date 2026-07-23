(ns kontor.l10n-uk.chart
  "UK nominal-ledger starter chart loader. Mirrors the FR/DE/AT pattern —
   the codes the shipped l10n-uk P&L + Balance Sheet reference (note 197).
   GBP-denominated."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [datahike.api :as d]))

(defn load-chart []
  (-> "kontor/l10n_uk/chart.edn" io/resource slurp edn/read-string))

(defn- ensure-gbp []
  {:kontor.commodity/symbol "GBP" :kontor.commodity/name "Pound sterling"
   :kontor.commodity/precision 2 :kontor.commodity/iso-4217 "GBP"})

(defn- account-tx
  [{:keys [code path type name reconcilable?]}]
  {:kontor.account/path path :kontor.account/code code :kontor.account/name name
   :kontor.account/type type :kontor.account/active true
   :kontor.account/commodity [:kontor.commodity/symbol "GBP"]
   :kontor.account/reconcilable (boolean reconcilable?)})

(defn install!
  ([conn] (install! conn (load-chart)))
  ([conn chart]
   (d/transact conn [(ensure-gbp)])
   (d/transact conn (mapv account-tx chart))))

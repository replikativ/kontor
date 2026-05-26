(ns kontor.l10n-de.chart
  "SKR04 chart of accounts loader.

   Reads `resources/kontor/l10n_de/skr04.edn` and
   transacts the entries into a kontor connection.
   Idempotent: account paths are :db.unique/identity in the kernel
   schema, so re-installing replaces values without duplication.

   Tags from the SKR04 EDN are materialized as `:account-tag/*`
   entities (one per distinct tag keyword) and linked via
   `:account/tags`. The l10n-de UStVA report (`./ustva.clj`)
   aggregates by these tags.

   Categories from `bank-de`'s `parse-statement` map onto SKR04
   contra accounts via `category->contra` — used as the default
   `:contra-resolver` in the bank-import → posting bridge."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [datahike.api :as d]))

;; ============================================================================
;; Resource loading
;; ============================================================================

(defn load-skr04
  "Read the SKR04 EDN. Returns a vector of account spec maps."
  []
  (-> "kontor/l10n_de/skr04.edn"
      io/resource
      slurp
      edn/read-string))

;; ============================================================================
;; Tag materialization
;; ============================================================================

(defn- distinct-tags
  "Every tag keyword referenced anywhere in the chart, deduplicated."
  [skr04]
  (->> skr04 (mapcat :tags) distinct vec))

(defn- tag-tx-data
  [tags]
  (mapv (fn [tag]
          {:account-tag/name (name tag)
           :account-tag/country-code "DE"
           :account-tag/applicability :account})
        tags))

;; ============================================================================
;; Account materialization
;; ============================================================================

(defn- ensure-eur
  "Idempotent EUR commodity. SKR04 is EUR-only by definition."
  []
  {:kontor.commodity/symbol "EUR"
   :kontor.commodity/name "Euro"
   :kontor.commodity/precision 2
   :kontor.commodity/iso-4217 "EUR"})

(defn- account-tx-entry
  "Build the kernel-side account entity-map for one SKR04 entry.
   :account/path is the unique identity; tags become refs via
   the materialized :account-tag entities."
  [{:keys [code path type name reconcilable? tags]}]
  (cond-> {:account/path        path
           :account/code        code
           :account/name        name
           :account/type        type
           :account/active      true
           :account/commodity   [:kontor.commodity/symbol "EUR"]
           :account/reconcilable (boolean reconcilable?)}
    (seq tags)
    (assoc :account/tags
           (mapv (fn [t] [:account-tag/name (clojure.core/name t)]) tags))))

;; ============================================================================
;; Public installer
;; ============================================================================

(defn install!
  "Transact the SKR04 chart + the per-tag :account-tag entities into
   `conn`. Idempotent. Returns the resulting tx-report from the
   final transact (or nil if nothing was needed)."
  ([conn] (install! conn (load-skr04)))
  ([conn skr04]
   ;; 1. EUR commodity
   (d/transact conn [(ensure-eur)])
   ;; 2. Tags first (so account-tx refs resolve)
   (d/transact conn (tag-tx-data (distinct-tags skr04)))
   ;; 3. Accounts
   (d/transact conn (mapv account-tx-entry skr04))))

;; ============================================================================
;; Bank-de category → SKR04 contra account
;; ============================================================================

(def category->contra-code
  "Map openclaw bank-de's auto-categorizer keywords onto SKR04 Konto
   numbers. Used by the bank-import → posting bridge as the
   default `:contra-resolver`. Override in the consumer when finer-
   grained control is needed."
  {;; Income
   :einnahmen          "4400"  ; Erlöse 19%
   :gehalt             "6020"
   ;; Operating expenses
   :buero              "6800"
   :telekommunikation  "6820"
   :software           "6815"
   :werbung            "6600"
   :reisekosten        "6650"
   :bewirtung          "6670"
   :fahrzeuge          "6825"
   :miete              "6300"
   :nebenkosten        "6400"
   :versicherung       "6520"
   :steuerberater      "6850"
   :beitraege          "6530"
   ;; Catch-all
   :sonstige-betriebsausgaben "6900"})

(defn category->contra-eid
  "Resolve `category` to a contra-account `:db/id` against `db`.
   Returns nil if the category is unknown — the consumer must
   handle this (typically: route to a manual-review queue)."
  [db category]
  (when-let [code (category->contra-code category)]
    (some-> (d/q '[:find ?a .
                   :in $ ?code
                   :where [?a :account/code ?code]]
                 db code))))

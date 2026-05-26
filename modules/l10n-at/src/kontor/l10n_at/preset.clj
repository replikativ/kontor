(ns kontor.l10n-at.preset
  "One-call AT preset — turns the multi-step prerequisite-aware install
   dance into a single `(install-all! conn)` call. Documents the right
   order, the prerequisites between statutes, and the default journals
   so a consumer doesn't have to discover them.

   Per note 168 §S10 (one-preset-per-module sweep).

   What it installs:
   - Kernel schema (re-call is idempotent — `create-test-db` already loads it)
   - EUR commodity (via chart)
   - Default journals: GJ / CR / CD / SJ / PJ
   - AT chart of accounts (RLG-1-aligned skeleton)
   - Statutes: CGT first (KESt rates + §10 thresholds + CIT rate live here),
     then investment-income (depends on the CGT parameters by code).

   AT does not yet ship an ADR-101 CIT statute; the period-tax provider
   carries the rate (record-shape, pre-ADR-101).

   Idempotent — re-running installs nothing new (identity attrs handle dedup)."
  (:require [datahike.api :as d]
            [kontor.core :as core]
            [kontor.l10n-at.cgt-statute :as cgt-statute]
            [kontor.l10n-at.chart :as chart]
            [kontor.l10n-at.investment-income-provider]  ; load compute-fns
            [kontor.l10n-at.investment-income-statute :as inv-statute]))

(def ^:private default-journals
  [{:kontor.journal/code "GJ" :kontor.journal/type :general :kontor.journal/name "Hauptbuch (General Journal)"}
   {:kontor.journal/code "CR" :kontor.journal/type :cash    :kontor.journal/name "Einzahlungen (Cash Receipts)"}
   {:kontor.journal/code "CD" :kontor.journal/type :cash    :kontor.journal/name "Auszahlungen (Cash Disbursements)"}
   {:kontor.journal/code "SJ" :kontor.journal/type :sale    :kontor.journal/name "Verkäufe (Sales Journal)"}
   {:kontor.journal/code "PJ" :kontor.journal/type :purchase :kontor.journal/name "Einkäufe (Purchase Journal)"}])

(defn install-all!
  "Install everything an AT consumer needs to start booking + producing
   reports + period taxes. See namespace docstring."
  [conn]
  (cgt-statute/install! conn)
  (inv-statute/install! conn)
  (chart/install! conn)
  (d/transact conn default-journals)
  conn)

(defn create-at-db
  "Convenience for tests / scripts: `(create-test-db)` + `(install-all!)`.
   Returns the connection."
  []
  (let [conn (core/create-test-db)]
    (install-all! conn)
    conn))

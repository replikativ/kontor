(ns kontor.l10n-au.preset
  "One-call AU preset — turns the multi-step prerequisite-aware install
   dance into a single `(install-all! conn)` call.

   Per note 168 §S10 (one-preset-per-module sweep).

   What it installs:
   - Kernel schema (re-call is idempotent)
   - AUD commodity (via chart)
   - Default journals: GJ / CR / CD / SJ / PJ
   - AU chart of accounts
   - Statutes: CGT (Div 115 + Subdiv 152 + Div 118-B), investment-income
     (franking credits + foreign-credits via §770).

   AU does not yet ship an ADR-101 CIT statute; the period-tax provider
   carries the rate (record-shape, pre-ADR-101).

   Idempotent."
  (:require [datahike.api :as d]
            [kontor.core :as core]
            [kontor.l10n-au.cgt-statute :as cgt-statute]
            [kontor.l10n-au.chart :as chart]
            [kontor.l10n-au.investment-income-provider]  ; load compute-fns
            [kontor.l10n-au.investment-income-statute :as inv-statute]))

(def ^:private default-journals
  [{:journal/code "GJ" :journal/type :general :journal/name "General Journal"}
   {:journal/code "CR" :journal/type :cash    :journal/name "Cash Receipts"}
   {:journal/code "CD" :journal/type :cash    :journal/name "Cash Disbursements"}
   {:journal/code "SJ" :journal/type :sale    :journal/name "Sales Journal"}
   {:journal/code "PJ" :journal/type :purchase :journal/name "Purchase Journal"}])

(defn install-all!
  "Install everything an AU consumer needs to start booking + producing
   reports + period taxes. See namespace docstring."
  [conn]
  (cgt-statute/install! conn)
  (inv-statute/install! conn)
  (chart/install! conn)
  (d/transact conn default-journals)
  conn)

(defn create-au-db
  "Convenience for tests / scripts: `(create-test-db)` + `(install-all!)`.
   Returns the connection."
  []
  (let [conn (core/create-test-db)]
    (install-all! conn)
    conn))

(ns kontor.l10n-fr.preset
  "One-call FR preset — turns the multi-step prerequisite-aware install
   dance into a single `(install-all! conn)` call.

   Per note 168 §S10 (one-preset-per-module sweep).

   What it installs:
   - Kernel schema (re-call is idempotent)
   - EUR commodity (via chart)
   - Default journals: GJ / CR / CD / SJ / PJ
   - PCG (Plan Comptable Général) skeleton
   - Statutes in order: CIT (Impôt sur les Sociétés — IS standard + PME
     bracket, CIR / CGE credits), CGT (PFU 31.4 % + barème, mobilière
     abattements, immobilière dual IR/PS ladders), investment-income
     (depends on CGT PS placement-rate + PFU IR-rate parameters).

   Idempotent."
  (:require [datahike.api :as d]
            [kontor.core :as core]
            [kontor.l10n-fr.cgt-statute :as cgt-statute]
            [kontor.l10n-fr.chart :as chart]
            [kontor.l10n-fr.cit-statute :as cit-statute]
            [kontor.l10n-fr.investment-income-provider]  ; load compute-fns
            [kontor.l10n-fr.investment-income-statute :as inv-statute]))

(def ^:private default-journals
  [{:journal/code "GJ" :journal/type :general :journal/name "Journal Général (General Journal)"}
   {:journal/code "CR" :journal/type :cash    :journal/name "Encaissements (Cash Receipts)"}
   {:journal/code "CD" :journal/type :cash    :journal/name "Décaissements (Cash Disbursements)"}
   {:journal/code "SJ" :journal/type :sale    :journal/name "Ventes (Sales Journal)"}
   {:journal/code "PJ" :journal/type :purchase :journal/name "Achats (Purchase Journal)"}])

(defn install-all!
  "Install everything an FR consumer needs to start booking + producing
   reports + period taxes. See namespace docstring."
  [conn]
  (cit-statute/install! conn)
  (cgt-statute/install! conn)
  (inv-statute/install! conn)
  (chart/install! conn)
  (d/transact conn default-journals)
  conn)

(defn create-fr-db
  "Convenience for tests / scripts: `(create-test-db)` + `(install-all!)`.
   Returns the connection."
  []
  (let [conn (core/create-test-db)]
    (install-all! conn)
    conn))

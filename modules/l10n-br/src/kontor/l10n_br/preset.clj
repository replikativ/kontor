(ns kontor.l10n-br.preset
  "One-call BR preset — turns the multi-step prerequisite-aware install
   dance into a single `(install-all! conn)` call.

   Per note 168 §S10 (one-preset-per-module sweep).

   What it installs:
   - Kernel schema (re-call is idempotent)
   - BRL commodity (via chart)
   - Default journals: GJ / CR / CD / SJ / PJ
   - BR chart of accounts
   - Statutes in order: CIT (Lucro Real / Presumido), CGT (4-bracket
     ladder + B3 swing), investment-income (PF / PJ; pre/post-2026
     dividend regime).

   Idempotent."
  (:require [datahike.api :as d]
            [kontor.core :as core]
            [kontor.l10n-br.cgt-statute :as cgt-statute]
            [kontor.l10n-br.chart :as chart]
            [kontor.l10n-br.cit-statute :as cit-statute]
            [kontor.l10n-br.investment-income-provider]  ; load compute-fns
            [kontor.l10n-br.investment-income-statute :as inv-statute]))

(def ^:private default-journals
  [{:journal/code "GJ" :journal/type :general :journal/name "Diário Geral (General Journal)"}
   {:journal/code "CR" :journal/type :cash    :journal/name "Recebimentos (Cash Receipts)"}
   {:journal/code "CD" :journal/type :cash    :journal/name "Pagamentos (Cash Disbursements)"}
   {:journal/code "SJ" :journal/type :sale    :journal/name "Vendas (Sales Journal)"}
   {:journal/code "PJ" :journal/type :purchase :journal/name "Compras (Purchase Journal)"}])

(defn install-all!
  "Install everything a BR consumer needs to start booking + producing
   reports + period taxes. See namespace docstring."
  [conn]
  (cit-statute/install! conn)
  (cgt-statute/install! conn)
  (inv-statute/install! conn)
  (chart/install! conn)
  (d/transact conn default-journals)
  conn)

(defn create-br-db
  "Convenience for tests / scripts: `(create-test-db)` + `(install-all!)`.
   Returns the connection."
  []
  (let [conn (core/create-test-db)]
    (install-all! conn)
    conn))

(ns kontor.l10n-in.preset
  "One-call IN preset — turns the multi-step prerequisite-aware install
   dance into a single `(install-all! conn)` call.

   Per note 168 §S10 (one-preset-per-module sweep).

   What it installs:
   - Kernel schema (re-call is idempotent)
   - INR commodity (via chart)
   - Default journals: GJ / CR / CD / SJ / PJ
   - IN chart of accounts
   - Statutes in order: CIT (Income-tax Act §115BAA / §115BAB / MAT),
     CGT (post-FA-2024 12.5 % LTCG / 20 % STCG + CII indexation + §54
     family), investment-income (§194/§194A/§194K TDS + §80TTA/§80TTB
     deductions + FA 2024 surcharge cap).

   Idempotent."
  (:require [datahike.api :as d]
            [kontor.core :as core]
            [kontor.l10n-in.cgt-statute :as cgt-statute]
            [kontor.l10n-in.chart :as chart]
            [kontor.l10n-in.cit-statute :as cit-statute]
            [kontor.l10n-in.investment-income-provider]  ; load compute-fns
            [kontor.l10n-in.investment-income-statute :as inv-statute]))

(def ^:private default-journals
  [{:kontor.journal/code "GJ" :kontor.journal/type :general :kontor.journal/name "General Journal"}
   {:kontor.journal/code "CR" :kontor.journal/type :cash    :kontor.journal/name "Cash Receipts"}
   {:kontor.journal/code "CD" :kontor.journal/type :cash    :kontor.journal/name "Cash Disbursements"}
   {:kontor.journal/code "SJ" :kontor.journal/type :sale    :kontor.journal/name "Sales Journal"}
   {:kontor.journal/code "PJ" :kontor.journal/type :purchase :kontor.journal/name "Purchase Journal"}])

(defn install-all!
  "Install everything an IN consumer needs to start booking + producing
   reports + period taxes. See namespace docstring."
  [conn]
  (cit-statute/install! conn)
  (cgt-statute/install! conn)
  (inv-statute/install! conn)
  (chart/install! conn)
  (d/transact conn default-journals)
  conn)

(defn create-in-db
  "Convenience for tests / scripts: `(create-test-db)` + `(install-all!)`.
   Returns the connection."
  []
  (let [conn (core/create-test-db)]
    (install-all! conn)
    conn))

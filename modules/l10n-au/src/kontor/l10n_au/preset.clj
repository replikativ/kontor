(ns kontor.l10n-au.preset
  "One-call AU preset — turns the multi-step prerequisite-aware install
   dance into a single `(install-all! conn)` call.

   Per(one-preset-per-module sweep).

   What it installs:
   - Kernel schema (re-call is idempotent)
   - AUD commodity (via chart)
   - Default journals: GJ / CR / CD / SJ / PJ
   - AU chart of accounts
   - Statutes: investment-income (ships the §23 ITRA 1986 corporate
     rate parameters CIT references by code), CGT (Div 115 + Subdiv 152
     + Div 118-B), CIT (the new ADR-101 statute; references the
     investment-income rate parameters + adds BRE history backfill
     rows), PIT (the new ADR-101 statute —
     §33 EStG bracket scale + Medicare + LITO).

   Order note: investment-income FIRST since
   it owns `AU.InvIncome.corporate-rate.*` which the new CIT statute
   references; CGT follows; CIT follows (its install! adds backfill
   rows to the BRE-rate parameter — safe even though CGT is in between
   because `:db.unique/identity` on `:kontor.parameter/code` makes
   value-row additions order-independent); PIT last.

   Idempotent."
  (:require [datahike.api :as d]
            [kontor.core :as core]
            [kontor.l10n-au.cgt-statute :as cgt-statute]
            [kontor.l10n-au.chart :as chart]
            [kontor.l10n-au.cit-provider]  ; no compute-fns yet, but symmetric
            [kontor.l10n-au.cit-statute :as cit-statute]
            [kontor.l10n-au.investment-income-provider]  ; load compute-fns
            [kontor.l10n-au.investment-income-statute :as inv-statute]
            [kontor.l10n-au.pit-provider]  ; load compute-fns (Medicare + LITO)
            [kontor.l10n-au.pit-statute :as pit-statute]))

(def ^:private default-journals
  [{:kontor.journal/code "GJ" :kontor.journal/type :general :kontor.journal/name "General Journal"}
   {:kontor.journal/code "CR" :kontor.journal/type :cash    :kontor.journal/name "Cash Receipts"}
   {:kontor.journal/code "CD" :kontor.journal/type :cash    :kontor.journal/name "Cash Disbursements"}
   {:kontor.journal/code "SJ" :kontor.journal/type :sale    :kontor.journal/name "Sales Journal"}
   {:kontor.journal/code "PJ" :kontor.journal/type :purchase :kontor.journal/name "Purchase Journal"}])

(defn install-all!
  "Install everything an AU consumer needs to start booking + producing
   reports + period taxes. See namespace docstring."
  [conn]
  (inv-statute/install! conn)
  (cgt-statute/install! conn)
  (cit-statute/install! conn)
  (pit-statute/install! conn)
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

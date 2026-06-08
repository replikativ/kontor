(ns kontor.l10n-us.preset
  "One-call US preset — turns the multi-step prerequisite-aware install
   dance into a single `(install-all! conn)` call.

   Per(one-preset-per-module sweep).

   What it installs:
   - Kernel schema (re-call is idempotent)
   - USD commodity (via chart)
   - Default journals: GJ / CR / CD / SJ / PJ
   - US chart of accounts (US GAAP skeleton)
   - Statutes: CIT first (§11 21 % flat-rate parameter; ADR-108 US slice),
     then CGT (§1(h) 0/15/20 brackets × filing-status + §1250 25 % +
     §1411 NIIT 3.8 %), then investment-income (§1(h)(11) qualified
     dividends + §103 muni-bond + §163(d) NII election + §901 FTC),
     then PIT (§1(j) brackets × 4 statuses × 6 years + §63 std deduction
     + §24 CTC/ACTC + cgt/investment-income lane folds; ADR-108 US slice).

   Federal-only — state income taxes are out of scope per ADR-005 /
   ADR-010 (consumers integrate Avalara / Vertex / TaxJar for
   sub-federal income / sales taxes).

   Idempotent."
  (:require [datahike.api :as d]
            [kontor.core :as core]
            [kontor.l10n-us.cgt-statute :as cgt-statute]
            [kontor.l10n-us.chart :as chart]
            [kontor.l10n-us.cit-provider]  ; no compute-fns yet, but symmetric load
            [kontor.l10n-us.cit-statute :as cit-statute]
            [kontor.l10n-us.investment-income-provider]  ; load compute-fns
            [kontor.l10n-us.investment-income-statute :as inv-statute]
            [kontor.l10n-us.pit-provider]  ; load compute-fns (CTC, ACTC, std-deduction)
            [kontor.l10n-us.pit-statute :as pit-statute]))

(def ^:private default-journals
  [{:kontor.journal/code "GJ" :kontor.journal/type :general :kontor.journal/name "General Journal"}
   {:kontor.journal/code "CR" :kontor.journal/type :cash    :kontor.journal/name "Cash Receipts"}
   {:kontor.journal/code "CD" :kontor.journal/type :cash    :kontor.journal/name "Cash Disbursements"}
   {:kontor.journal/code "SJ" :kontor.journal/type :sale    :kontor.journal/name "Sales Journal"}
   {:kontor.journal/code "PJ" :kontor.journal/type :purchase :kontor.journal/name "Purchase Journal"}])

(defn install-all!
  "Install everything a US consumer needs to start booking + producing
   reports + period taxes. See namespace docstring."
  [conn]
  (cit-statute/install! conn)
  (cgt-statute/install! conn)
  (inv-statute/install! conn)
  (pit-statute/install! conn)
  (chart/install! conn)
  (d/transact conn default-journals)
  conn)

(defn create-us-db
  "Convenience for tests / scripts: `(create-test-db)` + `(install-all!)`.
   Returns the connection."
  []
  (let [conn (core/create-test-db)]
    (install-all! conn)
    conn))

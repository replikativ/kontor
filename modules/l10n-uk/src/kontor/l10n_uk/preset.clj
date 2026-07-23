(ns kontor.l10n-uk.preset
  "One-call UK preset — turns the multi-step prerequisite-aware install
   dance into a single `(install-all! conn)` call.

   Per(one-preset-per-module sweep).

   What it installs:
   - Kernel schema (re-call is idempotent)
   - GBP commodity
   - UK nominal-ledger starter chart (`kontor.l10n-uk.chart` — the codes the
     shipped P&L + Balance Sheet reference; note 197)
   - Default journals: GJ / CR / CD / SJ / PJ
   - Statutes: CGT (AEA + 18/24 % std + BADR £1M cap + SSE corporate +
     post-Autumn-Budget-2024 rates), investment-income (CTA 2009 Part 9A
     distribution exemption + savings allowance + dividend allowance).

   UK does not yet ship an ADR-101 CIT statute; the period-tax provider carries
   the rate (record-shape, pre-ADR-101). The iXBRL filing gate is deferred.

   Idempotent."
  (:require [datahike.api :as d]
            [kontor.core :as core]
            [kontor.l10n-uk.chart :as chart]
            [kontor.l10n-uk.cgt-statute :as cgt-statute]
            [kontor.l10n-uk.investment-income-provider]  ; load compute-fns
            [kontor.l10n-uk.investment-income-statute :as inv-statute]))

(def ^:private default-commodity
  [{:kontor.commodity/symbol "GBP" :kontor.commodity/name "Pound Sterling"
    :kontor.commodity/precision 2}])

(def ^:private default-journals
  [{:kontor.journal/code "GJ" :kontor.journal/type :general :kontor.journal/name "General Journal"}
   {:kontor.journal/code "CR" :kontor.journal/type :cash    :kontor.journal/name "Cash Receipts"}
   {:kontor.journal/code "CD" :kontor.journal/type :cash    :kontor.journal/name "Cash Disbursements"}
   {:kontor.journal/code "SJ" :kontor.journal/type :sale    :kontor.journal/name "Sales Journal"}
   {:kontor.journal/code "PJ" :kontor.journal/type :purchase :kontor.journal/name "Purchase Journal"}])

(defn install-all!
  "Install everything a UK consumer needs to start booking + producing
   reports + period taxes. See namespace docstring."
  [conn]
  (d/transact conn default-commodity)
  (chart/install! conn)
  (cgt-statute/install! conn)
  (inv-statute/install! conn)
  (d/transact conn default-journals)
  conn)

(defn create-uk-db
  "Convenience for tests / scripts: `(create-test-db)` + `(install-all!)`.
   Returns the connection."
  []
  (let [conn (core/create-test-db)]
    (install-all! conn)
    conn))

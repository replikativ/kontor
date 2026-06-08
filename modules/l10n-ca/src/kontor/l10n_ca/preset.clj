(ns kontor.l10n-ca.preset
  "One-call CA preset — turns the multi-step prerequisite-aware install
   dance into a single
   `(install-all! conn)` call.

   What it installs:
   - Kernel schema (idempotent re-call)
   - CAD commodity (via the chart loader's `ensure-cad`)
   - Default journals: GJ (general), CR (cash-receipts), CD (cash-
     disbursements), SJ (sales), PJ (purchase)
   - CA chart of accounts (the chart resource shipped by
     `kontor.l10n-ca.chart`)
   - Statutes in order: CIT (T2 federal + per-province), CGT (50 %
     inclusion + LCGE + provincial), investment-income (eligible/
     non-eligible gross-up + DTC + §126 FTC + Part IV).

   After `install-all!`, the consumer can:
   - Post via `kontor.book/entry!` / `sell!` / `pay!`
   - Compute Trial Balance via `kontor.reporting.trial/trial-balance`
   - Run period taxes via `kontor.l10n-ca.cit-provider` /
     `…cgt-provider` / `…investment-income-provider`

   Idempotent. Note 160 §I-8."
  (:require [datahike.api :as d]
            [kontor.core :as core]
            [kontor.l10n-ca.cgt-statute :as cgt-statute]
            [kontor.l10n-ca.chart :as chart]
            [kontor.l10n-ca.cit-statute :as cit-statute]
            [kontor.l10n-ca.investment-income-provider]  ; load compute-fns
            [kontor.l10n-ca.investment-income-statute :as inv-statute]))

(def ^:private default-journals
  [{:kontor.journal/code "GJ" :kontor.journal/type :general :kontor.journal/name "General Journal"}
   {:kontor.journal/code "CR" :kontor.journal/type :cash    :kontor.journal/name "Cash Receipts"}
   {:kontor.journal/code "CD" :kontor.journal/type :cash    :kontor.journal/name "Cash Disbursements"}
   {:kontor.journal/code "SJ" :kontor.journal/type :sale    :kontor.journal/name "Sales Journal"}
   {:kontor.journal/code "PJ" :kontor.journal/type :purchase :kontor.journal/name "Purchase Journal"}])

(defn install-all!
  "Install everything a CA consumer needs to start booking + producing
   reports + period taxes. See namespace docstring."
  [conn]
  (cit-statute/install! conn)
  (cgt-statute/install! conn)
  (inv-statute/install! conn)
  (chart/install! conn)
  (d/transact conn default-journals)
  conn)

(defn create-ca-db
  "Convenience for tests / scripts: `(create-test-db)` + `(install-all!)`.
   Returns the connection."
  []
  (let [conn (core/create-test-db)]
    (install-all! conn)
    conn))

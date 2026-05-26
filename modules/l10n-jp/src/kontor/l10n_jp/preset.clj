(ns kontor.l10n-jp.preset
  "One-call JP preset — turns the multi-step prerequisite-aware install
   dance into a single `(install-all! conn)` call.

   Per note 168 §S10 (one-preset-per-module sweep).

   What it installs:
   - Kernel schema (re-call is idempotent)
   - JPY commodity (via chart)
   - Default journals: GJ / CR / CD / SJ / PJ
   - JP chart of accounts
   - Statutes in order: CIT (5-component stack: national CIT + local CIT
     + 2026 defense surtax + prefecture enterprise tax + municipal
     inhabitants' tax), CGT (5-component: listed/unlisted/RE-short/
     RE-long/§31-3 + 復興 surtax + §35 ¥30M deduction), investment-
     income (depends on CGT for 復興 surtax).

   Idempotent."
  (:require [datahike.api :as d]
            [kontor.core :as core]
            [kontor.l10n-jp.cgt-statute :as cgt-statute]
            [kontor.l10n-jp.chart :as chart]
            [kontor.l10n-jp.cit-statute :as cit-statute]
            [kontor.l10n-jp.investment-income-provider]  ; load compute-fns
            [kontor.l10n-jp.investment-income-statute :as inv-statute]))

(def ^:private default-journals
  [{:kontor.journal/code "GJ" :kontor.journal/type :general :kontor.journal/name "総勘定元帳 (General Journal)"}
   {:kontor.journal/code "CR" :kontor.journal/type :cash    :kontor.journal/name "現金収入 (Cash Receipts)"}
   {:kontor.journal/code "CD" :kontor.journal/type :cash    :kontor.journal/name "現金支出 (Cash Disbursements)"}
   {:kontor.journal/code "SJ" :kontor.journal/type :sale    :kontor.journal/name "売上 (Sales Journal)"}
   {:kontor.journal/code "PJ" :kontor.journal/type :purchase :kontor.journal/name "仕入 (Purchase Journal)"}])

(defn install-all!
  "Install everything a JP consumer needs to start booking + producing
   reports + period taxes. See namespace docstring."
  [conn]
  (cit-statute/install! conn)
  (cgt-statute/install! conn)
  (inv-statute/install! conn)
  (chart/install! conn)
  (d/transact conn default-journals)
  conn)

(defn create-jp-db
  "Convenience for tests / scripts: `(create-test-db)` + `(install-all!)`.
   Returns the connection."
  []
  (let [conn (core/create-test-db)]
    (install-all! conn)
    conn))

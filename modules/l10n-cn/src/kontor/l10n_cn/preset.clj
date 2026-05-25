(ns kontor.l10n-cn.preset
  "One-call CN preset — turns the multi-step prerequisite-aware install
   dance into a single `(install-all! conn)` call.

   Per note 168 §S10 (one-preset-per-module sweep).

   What it installs:
   - Kernel schema (re-call is idempotent)
   - CNY commodity (via chart)
   - Default journals: GJ / CR / CD / SJ / PJ
   - CN chart of accounts (CAS-aligned skeleton)
   - Statutes: CGT (EIT 25 % fold + LAT 30/40/50/60 % progressive),
     investment-income (IIT 20 % cat 9 + EIT fold).

   CN does not yet ship an ADR-101 CIT statute; the period-tax provider
   carries the rate (record-shape, pre-ADR-101).

   Idempotent."
  (:require [datahike.api :as d]
            [kontor.core :as core]
            [kontor.l10n-cn.cgt-statute :as cgt-statute]
            [kontor.l10n-cn.chart :as chart]
            [kontor.l10n-cn.investment-income-provider]  ; load compute-fns
            [kontor.l10n-cn.investment-income-statute :as inv-statute]))

(def ^:private default-journals
  [{:journal/code "GJ" :journal/type :general :journal/name "总账 (General Journal)"}
   {:journal/code "CR" :journal/type :cash    :journal/name "现金收入 (Cash Receipts)"}
   {:journal/code "CD" :journal/type :cash    :journal/name "现金支出 (Cash Disbursements)"}
   {:journal/code "SJ" :journal/type :sale    :journal/name "销售 (Sales Journal)"}
   {:journal/code "PJ" :journal/type :purchase :journal/name "采购 (Purchase Journal)"}])

(defn install-all!
  "Install everything a CN consumer needs to start booking + producing
   reports + period taxes. See namespace docstring."
  [conn]
  (cgt-statute/install! conn)
  (inv-statute/install! conn)
  (chart/install! conn)
  (d/transact conn default-journals)
  conn)

(defn create-cn-db
  "Convenience for tests / scripts: `(create-test-db)` + `(install-all!)`.
   Returns the connection."
  []
  (let [conn (core/create-test-db)]
    (install-all! conn)
    conn))

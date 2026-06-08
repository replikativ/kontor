(ns kontor.l10n-cn.preset
  "One-call CN preset — turns the multi-step prerequisite-aware install
   dance into a single `(install-all! conn)` call.

   Per(one-preset-per-module sweep).

   What it installs:
   - Kernel schema (re-call is idempotent)
   - CNY commodity (via chart)
   - Default journals: GJ / CR / CD / SJ / PJ
   - CN chart of accounts (CAS-aligned skeleton)
   - Statutes: CGT (EIT 25 % fold + LAT 30/40/50/60 % progressive +
     the §4 ¶1 standard rate parameter `CN.EIT.standard-rate`),
     investment-income (IIT 20 % cat 9 + EIT fold), CIT (per ADR-101
     statute-as-data — §28 SLPE + HNTE + regional 15 % overrides +
     §10 / §26 / §30 R&D / §18 / §23 provisions),
     PIT (per ADR-101 — §3 ¶1 comprehensive-income brackets + §3 ¶2
     business-income override + §6 basic + statutory deductions +
     seven special-additional-deduction audit-trail provisions).

   Order note: CGT first (ships `CN.EIT.standard-rate`); then
   investment-income (consumes CGT params + emits the EIT base
   lanes); then CIT (consumes `CN.EIT.standard-rate` + the
   investment-income lanes through consumer-supplied `:inputs`); then
   PIT (independent — owns the §3 ¶1 bracket scale).

   Idempotent."
  (:require [datahike.api :as d]
            [kontor.core :as core]
            [kontor.l10n-cn.cgt-statute :as cgt-statute]
            [kontor.l10n-cn.chart :as chart]
            [kontor.l10n-cn.cit-provider]  ; load compute-fns (R&D super-deduction)
            [kontor.l10n-cn.cit-statute :as cit-statute]
            [kontor.l10n-cn.investment-income-provider]  ; load compute-fns
            [kontor.l10n-cn.investment-income-statute :as inv-statute]
            [kontor.l10n-cn.pit-provider]  ; no compute-fns yet, but symmetric
            [kontor.l10n-cn.pit-statute :as pit-statute]))

(def ^:private default-journals
  [{:kontor.journal/code "GJ" :kontor.journal/type :general :kontor.journal/name "总账 (General Journal)"}
   {:kontor.journal/code "CR" :kontor.journal/type :cash    :kontor.journal/name "现金收入 (Cash Receipts)"}
   {:kontor.journal/code "CD" :kontor.journal/type :cash    :kontor.journal/name "现金支出 (Cash Disbursements)"}
   {:kontor.journal/code "SJ" :kontor.journal/type :sale    :kontor.journal/name "销售 (Sales Journal)"}
   {:kontor.journal/code "PJ" :kontor.journal/type :purchase :kontor.journal/name "采购 (Purchase Journal)"}])

(defn install-all!
  "Install everything a CN consumer needs to start booking + producing
   reports + period taxes. See namespace docstring."
  [conn]
  (cgt-statute/install! conn)
  (inv-statute/install! conn)
  (cit-statute/install! conn)
  (pit-statute/install! conn)
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
